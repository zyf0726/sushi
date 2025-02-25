package sushi;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ParserProperties;

import sushi.exceptions.CheckClasspathException;
import sushi.exceptions.InternalUnexpectedException;
import sushi.exceptions.TerminationException;
import sushi.exceptions.ToolAbortException;
import sushi.execution.ExecutionManager;
import sushi.execution.ExecutionResult;
import sushi.execution.Tool;
import sushi.execution.bestpath.BestPath;
import sushi.execution.evosuite.Evosuite;
import sushi.execution.evosuite.RunHeapSyn;
import sushi.execution.javac.Javac;
import sushi.execution.jbse.JBSEMethods;
import sushi.execution.jbse.JBSETraces;
import sushi.execution.listpaths.ListPaths;
import sushi.execution.loopend.LoopEnd;
import sushi.execution.loopmgr.LoopMgr;
import sushi.execution.merger.Merger;
import sushi.execution.minimizer.Minimizer;
import sushi.logging.Logger;
import sushi.util.ClassReflectionUtils;
import sushi.util.DirectoryUtils;

public class Main {
	/** The configuration {@link Options}. */
	private final Options options;
	
	/** A flag that indicates if the global timeout has expired. */
	private boolean timedOut;

	public Main(Options options) { 
		this.options = options;
	}
	
	public int start() {
		long start = System.currentTimeMillis();
		Thread timeoutThread = null;
		try {
			configureLogger();
			final Logger logger = new Logger(Main.class);

			logger.info("This is " + getName() + ", version " + getVersion() + ", " + '\u00a9' + " 2015-2021 " + getVendor());

			checkPrerequisites(logger);
			
			final Tool<?>[] tools;
			final int repeatFrom;
			final Evosuite toolEvosuite = new Evosuite(this.options);
			switch (this.options.getCoverage()) {
			case PATHS:
				tools = new Tool[]{ new JBSEMethods(this.options, true), new Merger(this.options), new ListPaths(this.options), new Javac(this.options), toolEvosuite, new LoopEnd() };
				repeatFrom = -1;
				break;
			case UNSAFE:
				tools = new Tool[]{ new JBSEMethods(this.options, true), new Merger(this.options), new ListPaths(this.options), new Javac(this.options), toolEvosuite, new LoopEnd() };
				repeatFrom = -1;
				break;
			case BRANCHES:
				tools = new Tool[]{ new JBSEMethods(this.options, true), new Merger(this.options), new Minimizer(this.options), new Javac(this.options), toolEvosuite, new LoopMgr(this.options) };
				repeatFrom = 2;
				break;
			default:
				logger.error("Unexpected internal error: unexpected value for -cov option");
				interruptThread(timeoutThread);
				return 2;
			}

			final boolean doEverything = (this.options.getPhases() == null);

			timeoutThread = makeGlobalTimeoutThread();
			
			RunHeapSyn heapsyn = this.options.getHeapSynRunner();
			if (heapsyn != null) {
				heapsyn.init();
			}

			doMainToolsLoop(logger, tools, repeatFrom, doEverything);
			
			long elapsed = System.currentTimeMillis() - start;
			logger.info(getName() + " terminates, elapsed " + elapsed/1000.0 + " seconds");
			logger.info("statistics: number of generated test = " + toolEvosuite.getNumGeneratedTest());
			if (this.options.getCoverage() == Coverage.BRANCHES) {
				logger.info("statistics: number of covered branches = " + toolEvosuite.getNumGlobalCoveredBranches());
			}
			if (toolEvosuite.getNumRun() > 0) {
				logger.info("statistics: number of Evosuite run (successful) = " + toolEvosuite.getNumSuccRun());
				logger.info("statistics: number of Evosuite run (failed) = " + toolEvosuite.getNumFailRun());
				logger.info("statistics: average Evosuite running time (successful) = " + toolEvosuite.getAvgSuccTime()/1000 + " (s)");
				logger.info("statistics: average Evosuite running time (failed) = " + toolEvosuite.getAvgFailTime()/1000 + " (s)");
			}
			if (heapsyn != null) {
				logger.info("statistics: number of HeapSyn run (successful) = " + heapsyn.getNumSuccRun());
				logger.info("statistics: number of HeapSyn run (failed) = " + heapsyn.getNumFailRun());
				logger.info("statistics: average HeapSyn running time (successful) = " + heapsyn.getAvgSuccTime()/1000 + " (s)");
				logger.info("statistics: average HeapSyn running time (failed) = " + heapsyn.getAvgFailTime()/1000 + " (s)");
			}
			interruptThread(timeoutThread);
			return 0;
		} catch (CheckClasspathException e) {
			interruptThread(timeoutThread);
			return 1;
		} catch (ToolAbortException e) {
			interruptThread(timeoutThread);
			return 1;
		} catch (IOException e) {
			interruptThread(timeoutThread);
			return 1;
		} catch (InternalUnexpectedException e) {
			interruptThread(timeoutThread);
			return 2;
		}
	}
	
    /**
     * Configures the logger.
     * 
     * @throws InternalUnexpectedException if some unexpected logging level
     *         is encountered. 
     */
	private void configureLogger() throws InternalUnexpectedException {
		switch (this.options.getLogLevel()) {
		case DEBUG:
			Logger.setLevel(sushi.logging.Level.DEBUG);
			break;
		case INFO:
			Logger.setLevel(sushi.logging.Level.INFO);
			break;
		case WARN:
			Logger.setLevel(sushi.logging.Level.WARN);
			break;
		case ERROR:
			Logger.setLevel(sushi.logging.Level.ERROR);
			break;
		case FATAL:
			Logger.setLevel(sushi.logging.Level.FATAL);
			break;
		default:
			throw new InternalUnexpectedException("Unrecognized logging level " + this.options.getLogLevel());
		}
	}
	
    /**
     * Checks prerequisites.
     * 
     * @param logger a {@link Logger}.
     * @throws CheckClasspathException if some required element in the provided
     *         paths does not exist or is not as expected.
     * @throws IOException if some I/O exception occurs while trying to create
     *         the temporary directories.
     */
	private void checkPrerequisites(Logger logger) throws CheckClasspathException, IOException {
		//check classpath: if the class is not found it raises an exception
		logger.debug("Checking classpath");
		final String className = (this.options.getTargetMethod() == null ?
				this.options.getTargetClass() :
				this.options.getTargetMethod().get(0));
		try {
			final ClassLoader ic = ClassReflectionUtils.getInternalClassloader(this.options);
			ic.loadClass(className.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			logger.error("Could not find class under test: " + className);
			throw new CheckClasspathException(e);
		}
		logger.debug("Classpath OK");
		
		//checks the presence of EvoSuite
		logger.debug("Checking EvoSuite");
		if (!Files.exists(this.options.getEvosuitePath()) || !Files.isReadable(this.options.getEvosuitePath())) {
			logger.error("Could not find or execute EvoSuite");
			throw new CheckClasspathException("Could not find or execute EvoSuite");
		}
		logger.debug("EvoSuite OK");
		
		//checks the presence of (or creates) the temporary directories 
		DirectoryUtils.possiblyCreateTmpDir(this.options);
	}
	
	/**
	 * Makes and runs a thread that detects when the global
	 * timeout expires.
	 */
	private Thread makeGlobalTimeoutThread() {
		this.timedOut = false;
		if (this.options.getGlobalBudget() > 0) {
			Thread chrono = new Thread(() -> {
				try {
					Thread.sleep(this.options.getGlobalBudget() * 1000);
				} catch (InterruptedException e) {
					//should never happen, in any case fallthrough
					//should be ok
				}
				setTimedOut();
			});
			chrono.start();
			return chrono;
		}
		return null;
	}
	
	private void interruptThread(Thread chrono) {
		if (chrono != null)
			chrono.interrupt();
	}
	
	private synchronized void setTimedOut() {
		this.timedOut = true;
	}
	
	private synchronized boolean timedOut() {
		return this.timedOut;
	}
	
	private void doMainToolsLoop(Logger logger, Tool<?>[] tools, int repeatFrom, boolean doEverything) 
	throws ToolAbortException {
		int currentPhase = 1;
		int nextToolIndex = 0;
		int lastRequiredPhase = (doEverything ? -1 : Collections.max(this.options.getPhases()));
		while (true) {
			final Tool<?> tool = tools[nextToolIndex];
			if (doEverything || this.options.getPhases().contains(currentPhase)) {
				logger.info("Phase " + currentPhase + ": executing tool " + tool.getName());
				final ExecutionResult[] result;
				try {
					result = ExecutionManager.execute(tool);
				} catch (TerminationException e) {
					if (e.getMessage() != null) {
						logger.info(e.getMessage());
					}
					break;
				}
				tool.reset();
				for (int i = 0; i < result.length; ++i) {
					if (result[i] != null && result[i].getExitStatus() != 0) {
						logger.error("Tool " + tool.getName() + " task " + tool.tasks().get(i / tool.redundance()) + " terminated with exit status " + result[i].getExitStatus());
						throw new ToolAbortException();
					}
				}
			} else if (currentPhase > lastRequiredPhase) {
				break;
			}
			++currentPhase;
			nextToolIndex = (nextToolIndex == tools.length - 1 ? repeatFrom : nextToolIndex + 1);
			if (timedOut()) {
				logger.info("Global time budget exhausted");
				break;
			}
		}
	}

    /**
     * Returns the name of this application, as resulting
     * from the containing jar file.
     * 
     * @return a {@link String} or {@code null} if this 
     *         class is not packaged in a jar file.
     */
    private static String getName() {
        return Main.class.getPackage().getImplementationTitle();
    }

    /**
     * Returns the vendor of this application, as resulting
     * from the containing jar file.
     * 
     * @return a {@link String} or {@code null} if this 
     *         class is not packaged in a jar file.
     */
    private static String getVendor() {
        return Main.class.getPackage().getImplementationVendor();
    }

    /**
     * Returns the version of this application, as resulting
     * from the containing jar file.
     * 
     * @return a {@link String} or {@code null} if this 
     *         class is not packaged in a jar file.
     */
    private static String getVersion() {
        return Main.class.getPackage().getImplementationVersion();
    }

    //Here starts the static part of the class, for managing the command line

	public static void main(String[] args) {
		final Options options = new Options();
		
        //parses options from the command line and exits if the command line
        //is ill-formed
		final CmdLineParser parser = new CmdLineParser(options, ParserProperties.defaults().withUsageWidth(200));
		try {
			parser.parseArgument(processArgs(args));
		} catch (CmdLineException e) {
			System.err.println("Error: " + e.getMessage());
			printUsage(parser);
			System.exit(1);
		}
		
		if (!options.isConsistent()) {
			System.err.println("Error: one of -target_class, -target_method, or -params_modifier_class options must be specified.");
			printUsage(parser);
			System.exit(1);
		}
		
		if (options.getHelp()) {
			printUsage(parser);
			System.exit(0);
		}
		
		configureOptions(options);

		final Main main = new Main(options);
		final int exitCode = main.start();
		System.exit(exitCode);
	}

    /**
     * Processes the command line arguments so they
     * can be parsed by the command line parser.
     * 
     * @param args the {@link String}{@code []} from the command line.
     * @return a processed {@link String}{@code []}.
     */
	private static String[] processArgs(final String[] args) {
		final Pattern argPattern = Pattern.compile("(-[a-zA-Z_-]+)=(.*)");
		final Pattern quotesPattern = Pattern.compile("^['\"](.*)['\"]$");
		final List<String> processedArgs = new ArrayList<String>();

		for (String arg : args) {
			final Matcher matcher = argPattern.matcher(arg);
			if (matcher.matches()) {
				processedArgs.add(matcher.group(1));
				final String value = matcher.group(2);
				final Matcher quotesMatcher = quotesPattern.matcher(value);
				if (quotesMatcher.matches()) {
					processedArgs.add(quotesMatcher.group(1));
				} else {
					processedArgs.add(value);
				}
			} else {
				processedArgs.add(arg);
			}
		}

		return processedArgs.toArray(new String[0]);
	}

    /**
     * Prints usage on the standard error.
     * 
     * @param parser a {@link CmdLineParser}.
     */
	private static void printUsage(final CmdLineParser parser) {
		System.err.println("Usage: java " + Main.class.getName() + " <options>");
		System.err.println("where <options> are:");
		// print the list of available options
		parser.printUsage(System.err);
	}
	
    /**
     * Applies a {@link OptionsConfigurator} to an {@link Options} object.
     * 
     * @param options an {@link Options} object. It must contain the information
     *        about the {@link OptionsConfigurator} that will be applied to 
     *        configure it.
     */
    private static void configureOptions(Options options) {
		if (options.getParametersModifierClassname() == null) {
			//no modifier
			return; 
		}
    	final URL url;
    	try {
    		url = options.getParametersModifierPath().toUri().toURL();
		} catch (MalformedURLException e) {
			System.err.println("Parameters modifier class home folder " + options.getParametersModifierPath() + " not found: " + e);
			return; 
		}
    	final OptionsConfigurator modi;
	    try {
	    	@SuppressWarnings("resource")
			final URLClassLoader loader = new URLClassLoader(new URL[] { url });
	    	final Class<? extends OptionsConfigurator> clazz =  
	            loader.loadClass(options.getParametersModifierClassname()).
	            asSubclass(OptionsConfigurator.class);
            modi = clazz.newInstance();
	    } catch (ClassNotFoundException e) {
	    	System.err.println("Parameters modifier class " + options.getParametersModifierClassname() + " not found: " + e);
			return; 
	    } catch (ClassCastException e) {
	    	System.err.println("Parameters modifier class " + options.getParametersModifierClassname() + " not a subclass of " + OptionsConfigurator.class.getCanonicalName() + ": " + e);
			return; 
	    } catch (InstantiationException e) {
	    	System.err.println("Parameters modifier class " + options.getParametersModifierClassname() + " cannot be instantiated or has no nullary constructor: " + e);
			return; 
		} catch (IllegalAccessException e) {
			System.err.println("Parameters modifier class " + options.getParametersModifierClassname() + " constructor is not visible: " + e);
			return; 
		}
    	modi.configure(options);
   }
}
