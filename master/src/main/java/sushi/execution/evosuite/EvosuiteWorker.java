package sushi.execution.evosuite;

import java.io.IOException;
import java.nio.file.Path;

import sushi.Options;
import sushi.exceptions.EvosuiteException;
import sushi.execution.ExecutionResult;
import sushi.execution.Worker;
import sushi.logging.Logger;
import sushi.util.DirectoryUtils;

public class EvosuiteWorker extends Worker {
	private static final Logger logger = new Logger(EvosuiteWorker.class);

	private final Options options;
	private final Evosuite evosuite;

	public EvosuiteWorker(Options options, Evosuite evosuite, int taskNumber) {
		super(taskNumber);
		this.options = options;
		this.evosuite = evosuite;
	}

	@Override
	public ExecutionResult call() throws EvosuiteException, InterruptedException {
		final String[] p = this.evosuite.getInvocationParameters(this.taskNumber);
		if (p == null) {
			logger.debug("Task " + this.taskNumber + ": no need to invoke Evosuite, cancelled");
			final ExecutionResult result = new ExecutionResult();
			result.setExitStatus(0);
			return result;
		}
		logger.debug("Task " + this.taskNumber + ": [" + this.evosuite.getNumRun() + 
				"] invoking " + this.evosuite.getCommandLine());
		
		final Path logFilePath = DirectoryUtils.getTmpDirPath(this.options).resolve("evosuite-task-" + this.taskNumber + "-" + Thread.currentThread().getName() + ".log");		
		final ProcessBuilder pb = new ProcessBuilder(p).redirectErrorStream(true);
		Process process = null; //to keep the compiler happy
		TestDetector td = null; //to keep the compiler happy
		try {
			final long start = System.currentTimeMillis();
			process = pb.start();
			td = new TestDetector(this.taskNumber, process.getInputStream(), logFilePath, this.evosuite.getTestGenerationNotifier());
			td.start();
			final int exitStatus = process.waitFor();
			final long elapsed = System.currentTimeMillis() - start;
//			this.evosuite.totTimeEvosuite += elapsed;
			if (this.evosuite.checkCompleted(taskNumber)) {
				this.evosuite.totTimeSucc += elapsed;
				++this.evosuite.numRunSucc;
				logger.debug("Task " + this.taskNumber + " completed, elapsed " + elapsed/1000 + " seconds");
			} else {
				this.evosuite.totTimeFail += elapsed;
				++this.evosuite.numRunFail;
				logger.debug("Task " + this.taskNumber + " ended, elapsed " + elapsed/1000 + " seconds");
			}
			td.join();
			final ExecutionResult result = new ExecutionResult();
			result.setExitStatus(exitStatus);
			return result;
		} catch (IOException e) {
			logger.error("I/O error while creating evosuite process or log file");
			throw new EvosuiteException(e);
		} catch (InterruptedException e) {
			if (td != null) {
				td.interrupt();
			}
			process.destroy();
			throw e;
		}
	}
}
