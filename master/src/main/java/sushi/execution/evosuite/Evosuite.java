package sushi.execution.evosuite;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import heapsyn.algo.Statement;
import heapsyn.heap.ObjectH;
import heapsyn.wrapper.symbolic.Specification;
import sushi.Options;
import sushi.exceptions.EvosuiteException;
import sushi.exceptions.UnhandledInternalException;
import sushi.execution.Coordinator;
import sushi.execution.Tool;
import sushi.execution.Worker;
import sushi.logging.Logger;
import sushi.util.ArrayUtils;
import sushi.util.DirectoryUtils;
import sushi.util.IOUtils;

public class Evosuite extends Tool<String[]> {
	private static final Logger logger = new Logger(Evosuite.class);
	
	private final Options options;
	private final EvosuiteCoordinator evosuiteCoordinator;
	private String commandLine;
	private ArrayList<Integer> tasks = null;
	
	private RunHeapSyn heapsynRunner;
	
	public boolean checkCompleted(int taskNumber) {
		return this.evosuiteCoordinator.checkCompleted(taskNumber);
	}
	
	public int getNumGeneratedTest() {
		return this.evosuiteCoordinator.getNumberGeneratedTest();
	}
	
	public int getNumGlobalCoveredBranches() {
		return this.evosuiteCoordinator.getNumberGlobalCoveredBranches();
	}
	
	int numRunSucc = 0, numRunFail = 0;
	long totTimeSucc = 0, totTimeFail = 0;
	
	public final long getTotalFailTime() {
		return this.totTimeFail;
	}
	
	public final long getTotalSuccTime() {
		return this.totTimeSucc;
	}
	
	public final double getAvgFailTime() {
		if (this.numRunFail != 0) {
			return (double) this.totTimeFail / this.numRunFail;
		} else {
			return -1000.0;
		}
	}
	
	public final double getAvgSuccTime() {
		if (this.numRunSucc != 0) {
			return (double) this.totTimeSucc / this.numRunSucc;
		} else {
			return -1000.0;
		}
	}
	
	public final long getTotalTime() {
		return this.totTimeFail + this.totTimeSucc;
	}
	
	public final int getNumFailRun() {
		return this.numRunFail;
	}
	
	public final int getNumSuccRun() {
		return this.numRunSucc;
	}
	
	public final int getNumRun() {
		return this.numRunFail + this.numRunSucc;
	}
	
/*
	int numRunEvosuite = 0;
	long totTimeEvosuite = 0;
	
	public int getNumRunEvosuite() {
		return this.numRunEvosuite;
	}
	
	public long getTotalTimeEvosuite() {
		return this.totTimeEvosuite;
	}
	
	public double getAvgTimeEvosuite() {
		if (this.numRunEvosuite != 0) {
			return (double) this.totTimeEvosuite / this.numRunEvosuite;
		} else {
			return -1.0;
		}
	}
*/

	public Evosuite(Options options) { 
		this.options = options;
		this.evosuiteCoordinator = new EvosuiteCoordinator(this, options);
		this.heapsynRunner = this.options.getHeapSynRunner();
	}

	public String getCommandLine() {
		return this.commandLine; 
	}
	
	public TestGenerationNotifier getTestGenerationNotifier() {
		return this.evosuiteCoordinator::onTestGenerated;
	}
	
	@Override
	public List<Integer> tasks() {
		if (this.tasks == null) {
			this.tasks = new ArrayList<>();
			final int numTasks;
			try {
				final int numPaths = (int) Files.lines(DirectoryUtils.getMinimizerOutFilePath(this.options)).count();
				numTasks = (numPaths / this.options.getNumMOSATargets()) + (numPaths % this.options.getNumMOSATargets() == 0 ? 0 : 1);
			} catch (IOException e) {
				logger.error("Unable to find and open minimizer output file " + DirectoryUtils.getMinimizerOutFilePath(this.options).toString());
				throw new EvosuiteException(e);
			}
			for (int i = 0; i < numTasks; ++i) {
				this.tasks.add(i);
			}
		}
		return this.tasks;
	}
	
	@Override
	public String[] getInvocationParameters(int taskNumber) {
		final ArrayList<Integer> targetMethodNumbers = new ArrayList<>();
		final ArrayList<Integer> traceNumbersLocal = new ArrayList<>();
		{
			Integer targetMethodNumber_ = null;
			Integer traceNumberLocal_ = null;
			try (final BufferedReader r = Files.newBufferedReader(DirectoryUtils.getMinimizerOutFilePath(this.options))) {
				String line;
				while ((line = r.readLine()) != null) {
					final String[] fields = line.split(",");
					targetMethodNumber_ = Integer.parseInt(fields[1].trim());
					traceNumberLocal_ = Integer.parseInt(fields[2].trim());
					if (targetMethodNumber_ == null || traceNumberLocal_ == null) {
						logger.error("Minimizer output file " + DirectoryUtils.getMinimizerOutFilePath(this.options).toString() + " ill-formed, or task number " + taskNumber + " is wrong");
						throw new EvosuiteException("Minimizer output file " + DirectoryUtils.getMinimizerOutFilePath(this.options).toString() + " ill-formed, or task number " + taskNumber + " is wrong");
					}
					targetMethodNumbers.add(targetMethodNumber_.intValue());
					traceNumbersLocal.add(traceNumberLocal_.intValue());
				}
			} catch (IOException e) {
				logger.error("I/O error while reading " + DirectoryUtils.getMinimizerOutFilePath(this.options).toString());
				throw new EvosuiteException(e);
			}
		}

		final ArrayList<String> targetMethodSignatures = new ArrayList<>();
		final String targetClassName, targetMethodSignature;
		//currently SUSHI tests at most a single class, so whatever will be
		//targetMethodName all the rows in the methods.txt file will have same
		//class name and we don't need to take a list of target class names for MOSA
		{
			String[] signature = null;
			if (this.options.getTargetMethod() == null) {
				try (final BufferedReader r = Files.newBufferedReader(DirectoryUtils.getMethodsFilePath(this.options))) {
					String line;
					while ((line = r.readLine()) != null) {
						signature = line.split(":");
						targetMethodSignatures.add(signature[2] + signature[1]);
					}
				} catch (IOException e) {
					logger.error("I/O error while reading " + DirectoryUtils.getMethodsFilePath(this.options).toString());
					throw new EvosuiteException(e);
				}

				if (signature == null) {
					logger.error("Methods file " + DirectoryUtils.getMethodsFilePath(this.options) + " and coverage file " + DirectoryUtils.getCoverageFilePath(this.options).toString() + " disagree");
					throw new EvosuiteException("Methods file " + DirectoryUtils.getMethodsFilePath(this.options) + " and coverage file " + DirectoryUtils.getCoverageFilePath(this.options).toString() + " disagree");
				}
			} else {
				signature = this.options.getTargetMethod().toArray(ArrayUtils.EMPTY_STRING_ARRAY);
			}

			targetClassName = signature[0].replace('/', '.');
			targetMethodSignature = signature[2] + signature[1];
			
			if (options.getTargetMethod() != null) {
				//must add the only targetMethodSignature to targetMethodSignatures, 
				//because the previous code skipped the loop that populates it
				targetMethodSignatures.add(targetMethodSignature);
			}
		}
		
		final List<String> evo = new ArrayList<String>();
		if (this.options.getJava8Path() != null && !this.options.getJava8Path().toString().equals("")) {
			evo.add(this.options.getJava8Path().resolve("bin/java").toString());
		} else {
			evo.add("java");
		}
		evo.add("-Xmx4G");
		evo.add("-jar");
		evo.add(this.options.getEvosuitePath().toString());
		evo.add("-class");
		evo.add(targetClassName);
		evo.add("-mem");
		evo.add("2048");
		evo.add("-Dmock_if_no_generator=false");
		evo.add("-Dreplace_system_in=false");
		evo.add("-Dreplace_gui=false");
		evo.add("-Dp_functional_mocking=0.0");
		evo.add("-DCP=" + getClassPath());
		evo.add("-Dassertions=false");
		evo.add("-Dreport_dir=" + DirectoryUtils.getTmpDirPath(this.options).toString());
		evo.add("-Djunit_suffix=_Test");
		evo.add("-Dsearch_budget=" + getTimeBudget());
		evo.add("-Dtest_dir=" + DirectoryUtils.getTmpDirPath(this.options).toString());
		evo.add("-Dvirtual_fs=false");
		evo.add("-Dselection_function=ROULETTEWHEEL");
		evo.add("-Dcriterion=PATHCONDITION");		
		evo.add("-Dsushi_statistics=true");
		evo.add("-Dinline=false");
		evo.add("-Dsushi_modifiers_local_search=true");
		//evo.add("-Dpath_condition_target=LAST_ONLY");  TODO this was for concolic, should we use it?
		evo.add("-Duse_minimizer_during_crossover=true");
		evo.add("-Davoid_replicas_of_individuals=true"); 
		evo.add("-Dno_change_iterations_before_reset=30");
        if (this.options.getEvosuiteNoDependency()) {
        	evo.add("-Dno_runtime_dependency");
        }
        evo.add("-Dpath_condition_evaluators_dir=" + DirectoryUtils.getTmpDirPath(this.options).toString());
        evo.add("-Demit_tests_incrementally=true");
        evo.add("-Dcrossover_function=SUSHI_HYBRID");
        evo.add("-Dalgorithm=DYNAMOSA");
        evo.add("-generateMOSuite");

		evo.addAll(this.options.getAdditionalEvosuiteArgs());

		this.commandLine = evo.toString().replaceAll("\\[", "").replaceAll("\\]", "").replaceAll(",", "");

		final StringBuilder optionPC = new StringBuilder("-Dpath_condition=");
		boolean firstDone = false;
		boolean needEvosuite = false;
		for (int i = this.options.getNumMOSATargets() * taskNumber; i < Math.min(this.options.getNumMOSATargets() * (taskNumber + 1), targetMethodNumbers.size()); ++i) {
			final int targetMethodNumber_i = targetMethodNumbers.get(i).intValue();
			final int traceNumberLocal_i = traceNumbersLocal.get(i).intValue();
			final String targetMethodSignature_i = targetMethodSignatures.get(targetMethodNumber_i);
			Path sof = DirectoryUtils.getTmpDirPath(this.options).resolve(DirectoryUtils.getSpecOutFilePath(this.options, targetMethodNumber_i, traceNumberLocal_i));
			Path tof = DirectoryUtils.getTmpDirPath(this.options).resolve(DirectoryUtils.getTestOutFilePath(this.options, targetMethodNumber_i, traceNumberLocal_i));
			final Specification spec;
			final ObjectH[] args;
			try {
				final List<Statement> stmts;
				if (this.heapsynRunner != null) {
					FileInputStream fis = new FileInputStream(sof.toString());
					ObjectInputStream ois = new ObjectInputStream(fis);
					spec = (Specification) ois.readObject();
					args = (ObjectH[]) ois.readObject();
					ois.close();
					fis.close();
					while (!evosuiteCoordinator.checkPrepared(taskNumber, targetMethodNumber_i, traceNumberLocal_i)) {
						try {
							logger.warn("EvosuiteCoordinator not prepared, sleep 1000 milliseconds");
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							logger.warn("sleep interrupted");
						}
					}
					logger.debug("Task " + taskNumber + ": [" + this.heapsynRunner.getNumRun() +
							"] invoking HeapSyn to generate a test for " +
							DirectoryUtils.getSpecOutFilePath(this.options, targetMethodNumber_i, traceNumberLocal_i));
					stmts = this.heapsynRunner.generateTest(spec, args);
				} else {
					stmts = null;
				}
				if (stmts != null) {
					String methodUnderTest = targetMethodSignature_i.substring(0, targetMethodSignature_i.indexOf('('));
					Statement.printStatements(stmts, methodUnderTest, new PrintStream(new FileOutputStream(tof.toString())));
					this.evosuiteCoordinator.onHeapSynTestGenerated(taskNumber, targetMethodNumber_i, traceNumberLocal_i);
				} else if (this.heapsynRunner == null || this.heapsynRunner.useEvosuiteIfFailed()) {
					needEvosuite = true;
					if (firstDone) {
						optionPC.append(":");
					} else {
						firstDone = true;
					}
					optionPC.append(targetClassName + "," + targetMethodSignature_i + "," + DirectoryUtils.getJBSEOutClassQualified(this.options, targetMethodNumber_i, traceNumberLocal_i));
				}
			} catch (IOException | NullPointerException | ClassNotFoundException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
				throw new UnhandledInternalException(e);
			} 
		}
		if (needEvosuite) {
			evo.add(optionPC.toString());
			this.commandLine += " " + optionPC.toString();
			return evo.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
		} else {
			return null;
		}
	}
	
	private String getClassPath() {
		return IOUtils.concatClassPath(
				IOUtils.concatClassPath(this.options.getClassesPath()),
				IOUtils.concatClassPath(this.options.getSushiLibPath(), this.options.getJBSELibraryPath()));
	}
	
	@Override
	public void reset() {
		this.tasks = null;
	}
	
	@Override
	public boolean delegateTimeoutToCoordinator() {
		return true; //pleonastic because the EvosuiteCoordinator knows it has to manage timeout
	}
	
	@Override
	public int getTimeBudget() {
		return this.options.getEvosuiteBudget();
	}

	@Override
	public Worker getWorker(int taskNumber) {
		return new EvosuiteWorker(this.options, this, taskNumber);
	}
	
	@Override
	public Coordinator getCoordinator() {
		return this.evosuiteCoordinator;
	}
	
	@Override
	public int degreeOfParallelism() {
		return (this.options.getParallelismEvosuite() == 0 ? tasks().size() * redundance() : this.options.getParallelismEvosuite());
	}
	
	@Override
	public int redundance() {
		return this.options.getRedundanceEvosuite();
	}
}
