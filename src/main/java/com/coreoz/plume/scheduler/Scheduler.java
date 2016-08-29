package com.coreoz.plume.scheduler;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coreoz.plume.scheduler.schedule.Schedule;
import com.coreoz.plume.scheduler.stats.SchedulerStats;
import com.coreoz.plume.scheduler.time.SystemTimeProvider;
import com.coreoz.plume.scheduler.time.TimeProvider;

/**
 * A job is executed only once at a time.
 * The scheduler will never execute the same job twice at a time.
 */
public class Scheduler {

	// TODO optimize log statements
	private static final Logger logger = LoggerFactory.getLogger(Scheduler.class);

	public static final int DEFAULT_THREAD_POOL_SIZE = 10;
	public static final long DEFAULT_MINIMUM_DELAY_TO_REPLACE_JOB = 10L;

	private final Jobs jobs;
	private final JobThreadPool threadPool;
	private final TimeProvider timeProvider;
	private final long minimumDelayInMillisToReplaceJob;

	private volatile int threadAvailableCount;
	private volatile boolean shuttingDown;

	public Scheduler() {
		this(DEFAULT_THREAD_POOL_SIZE);
	}

	public Scheduler(int nbThreads) {
		this(nbThreads, DEFAULT_MINIMUM_DELAY_TO_REPLACE_JOB);
	}

	public Scheduler(int nbThreads, long minimumDelayInMillisToReplaceJob) {
		this(nbThreads, minimumDelayInMillisToReplaceJob, new SystemTimeProvider());
	}

	public Scheduler(int nbThreads, long minimumDelayInMillisToReplaceJob,
			TimeProvider timeProvider) {
		this.jobs = new Jobs();
		this.minimumDelayInMillisToReplaceJob = minimumDelayInMillisToReplaceJob;
		this.threadPool = new JobThreadPool(nbThreads);
		this.timeProvider = timeProvider;

		this.threadAvailableCount = nbThreads;
		this.shuttingDown = false;
	}

	public synchronized Job schedule(String nullableName, Runnable runnable, Schedule when) {
		// TODO check not null

		String name = nullableName == null ? runnable.toString() : nullableName;
		// TODO check job non déjà importé

		Job job = Job.of(
			new AtomicReference<>(JobStatus.DONE),
			new AtomicLong(0L),
			new AtomicInteger(0),
			name,
			when,
			runnable
		);

		parkInPool(job, false);
		jobs.indexedByName().put(name, job);

		return job;
	}

	public Collection<Job> jobStatus() {
		return jobs.indexedByName().values();
	}

	public Optional<Job> findJob(String name) {
		return Optional.ofNullable(jobs.indexedByName().get(name));
	}

	/**
	 * Wait until the current running jobs are executed
	 * and cancel jobs that are planned to be executed
	 */
	public void gracefullyShutdown() {
		synchronized (jobs.nextExecutionsOrder()) {
			if(shuttingDown) {
				return;
			}

			shuttingDown = true;

			if(jobs.nextRunningJob() != null) {
				tryCancelNextExecution();
			}
		}

		// should be outside the synchronized block to avoid dead lock
		threadPool.gracefullyShutdown();
	}

	public SchedulerStats stats() {
		return SchedulerStats.of(threadPool.stats());
	}

	// package API

	void checkNextJobToRun(boolean shouldReuseCurrentThread) {
		synchronized (jobs.nextExecutionsOrder()) {
			logger.debug("begin nextExecutionsOrder : {}", jobs.nextExecutionsOrder().stream().map(Job::name).collect(Collectors.joining()));

			if(shouldReuseCurrentThread) {
				threadAvailableCount++;
			}

			if(jobs.nextExecutionsOrder().isEmpty()) {
				logger.trace("No more job to execute");
				return;
			}
			if(shuttingDown) {
				logger.trace("Shutting down...");
				return;
			}

			// if the next job to run will execute later than the next job in the queue
			// then the next job scheduled will be replaced by the next job in the queue
			Job nextJob = jobs.nextExecutionsOrder().get(0);
			if(jobs.nextRunningJob() != null
				&& jobs.nextRunningJob().job().status().get() == JobStatus.READY
				&& jobs.nextRunningJob().job().nextExecutionTimeInMillis().get()
					> (nextJob.nextExecutionTimeInMillis().get() + minimumDelayInMillisToReplaceJob)
			) {
				tryCancelNextExecution();
				// the next job will be executed right after
				// the cancel job in returned to the pool
			} else if(jobs.nextRunningJob() == null
				|| jobs.nextRunningJob().job().status().get() != JobStatus.READY) {
				runNextJob(shouldReuseCurrentThread);
			}
			logger.debug("end nextExecutionsOrder : {}", jobs.nextExecutionsOrder().stream().map(Job::name).collect(Collectors.joining()));
		}
	}

	void parkInPool(Job executed, boolean shouldReuseCurrentThread) {
		logger.trace(
			"parkInPool {} - running {}",
			executed.name(),
			jobs.nextRunningJob() == null ? "null" : jobs.nextRunningJob().job().name()
		);

		if(shuttingDown) {
			logger.trace("Shutting down...");
			return;
		}

		synchronized (jobs.nextExecutionsOrder()) {
			if(jobs.nextRunningJob() != null && jobs.nextRunningJob().job() == executed) {
				jobs.nextRunningJob(null);
			}
		}

		updateForNextExecution(executed);
		if(executed.status().get() == JobStatus.SCHEDULED) {
			synchronized (jobs.nextExecutionsOrder()) {
				jobs.nextExecutionsOrder().add(executed);
				jobs.nextExecutionsOrder().sort(Comparator.comparing(
					job -> job.nextExecutionTimeInMillis().get()
				));
			}
		} else {
			logger.info("The job {} won't be executed again", executed.name());
		}
		checkNextJobToRun(shouldReuseCurrentThread);
	}

	// internal

	private void tryCancelNextExecution() {
		jobs.nextRunningJob().shouldExecuteJob(false);
		synchronized (jobs.nextRunningJob().job()) {
			jobs.nextRunningJob().job().notifyAll();
		}
	}

	private void runNextJob(boolean shouldReuseCurrentThread) {
		if(shouldReuseCurrentThread) {
			// a previous job has already been executed on the current thread,
			// and the current thread is ready to execute another job.
			// so instead of returning the thread to the pool,
			// it is directly used to run the next job in the queue.
			nextRunningJob().run();
		}
		else if(threadAvailableCount > 0) {
			threadPool.submitJob(nextRunningJob());
			threadAvailableCount--;
		} else {
			logger.warn("Job thread pool is full, either tasks take too much time to execute "
					+ "or either the thread pool is too small");
		}
	}

	private RunningJob nextRunningJob() {
		jobs.nextRunningJob(new RunningJob(
			jobs.nextExecutionsOrder().remove(0),
			this,
			timeProvider
		));
		return jobs.nextRunningJob();
	}

	private Job updateForNextExecution(Job job) {
		// if the job has not been executed, do not recalculate the next execution time
		if(job.status().get() != JobStatus.READY) {
			job.nextExecutionTimeInMillis().set(
				job.schedule().nextExecutionInMillis(job.executionsCount().get(), timeProvider)
			);
		}

		if(job.nextExecutionTimeInMillis().get() > 0) {
			job.status().set(JobStatus.SCHEDULED);
		} else {
			job.status().set(JobStatus.DONE);
		}

		return job;
	}

}
