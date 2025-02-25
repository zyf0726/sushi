package sushi.execution.evosuite;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import heapsyn.algo.DynamicGraphBuilder;
import heapsyn.algo.Statement;
import heapsyn.algo.TestGenerator;
import heapsyn.algo.WrappedHeap;
import heapsyn.heap.ObjectH;
import heapsyn.heap.SymbolicHeap;
import heapsyn.heap.SymbolicHeapAsDigraph;
import heapsyn.smtlib.ExistExpr;
import heapsyn.wrapper.symbolic.Specification;
import heapsyn.wrapper.symbolic.SymbolicExecutor;
import heapsyn.wrapper.symbolic.SymbolicExecutorWithCachedJBSE;
import sushi.logging.Logger;

public class RunHeapSyn {
	
	private static final Logger logger = new Logger(RunHeapSyn.class);
	
	public static boolean DEBUG_MODE	= true;
	
	private final String TMP_DIR		= "tmp/";
	private final String GRAPHOBJ_EXT	= ".graph";
	private final String GRAPHTXT_EXT	= ".txt";
	
	private HeapSynParameters parameters;
	
	private TestGenerator testGenerator;
	
	private long timeBuildGraph;
	private long totTimeFail, totTimeSucc;
	private int numRunFail, numRunSucc;
	
	public RunHeapSyn(HeapSynParameters p) {
		this.parameters = p;
		this.testGenerator = null;
		this.timeBuildGraph = 0;
		this.totTimeFail = this.totTimeSucc = 0;
		this.numRunFail = this.numRunSucc = 0;
	}
	
	private List<WrappedHeap> buildGraph(HeapSynParameters p) {
		if (DEBUG_MODE == true) {
			try {
				List<WrappedHeap> heaps = WrappedHeap.importHeapsFrom(
						TMP_DIR + p.getTargetClass().getName() + GRAPHOBJ_EXT);
				logger.info("heap transformation graph imported");
				return heaps;
			} catch (IOException | ClassNotFoundException e) {
				logger.info("import heap transformation graph failed: " + e.getMessage());
			}
		}
		
		logger.info("building heap transformation graph for " + p.getTargetClass());
		long start = System.currentTimeMillis();
		
		SymbolicExecutor executor = new SymbolicExecutorWithCachedJBSE(
				p.getFieldFilter());
		DynamicGraphBuilder gb = new DynamicGraphBuilder(
				executor, getPublicMethods(p.getTargetClass()));
		for (Entry<Class<?>, Integer> entry : p.getHeapScope().entrySet()) {
			Class<?> cls = entry.getKey();
			int scope = entry.getValue();
			logger.debug("set heap scope of " + cls + " to " + scope);
			gb.setHeapScope(entry.getKey(), entry.getValue());
		}
		SymbolicHeap initHeap = new SymbolicHeapAsDigraph(ExistExpr.ALWAYS_TRUE);
		List<WrappedHeap> heaps = gb.buildGraph(initHeap, p.getMaxSeqLength());
		
		long elapsed = System.currentTimeMillis() - start;
		this.timeBuildGraph = elapsed;
		logger.info("heap transformation graph built, elapsed " + elapsed/1000 + " seconds");
		
		if (DEBUG_MODE == true) {
			try {
				DynamicGraphBuilder.__debugPrintOut(heaps, executor,
						new PrintStream(TMP_DIR + p.getTargetClass().getName() + GRAPHTXT_EXT));
				logger.info("heap transofrmation graph printed");
			} catch (IOException e) {
				logger.info("print heap transformation graph failed :" + e.getMessage());
			}
			try {
				WrappedHeap.exportHeapsTo(heaps, TMP_DIR + p.getTargetClass().getName() + GRAPHOBJ_EXT);
				logger.info("heap transofrmation graph exported");
			} catch (IOException e) {
				logger.info("export heap transformation graph failed :" + e.getMessage());
			}
		}
		
		return heaps;
	}
	
	public void init() {
		List<WrappedHeap> heaps = this.buildGraph(this.parameters);
		this.testGenerator = new TestGenerator(heaps);
	}
	
	public final boolean useEvosuiteIfFailed() {
		return false;
	}
	
	public synchronized final List<Statement> generateTest(Specification spec, ObjectH... args) {
		long start = System.currentTimeMillis();
		final List<Statement> stmts;
		if (spec != null) {
			stmts = testGenerator.generateTestWithSpec(spec, args);
		} else {
			logger.warn("HeapSyn: null specification, skipped");
			stmts = null;
		}
		long elapsed = System.currentTimeMillis() - start;
		if (stmts == null) { // failed
			numRunFail += 1;
			totTimeFail += elapsed;
			logger.debug("HeapSyn: generate failed, elapsed " + elapsed + " milliseconds");
		} else { // successful
			numRunSucc += 1;
			totTimeSucc += elapsed;
			logger.debug("HeapSyn: generate successfully, elpased " + elapsed + " milliseconds");
		}
		return stmts;
	}
	
	public final long getTimeBuildGraph() {
		return this.timeBuildGraph;
	}
	
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
	
	private static List<Method> getPublicMethods(Class<?> cls) {
		List<Method> decMethods = Arrays.asList(cls.getDeclaredMethods());
		List<Method> pubMethods = Arrays.asList(cls.getMethods());
		List<Method> methods = decMethods.stream()
				.filter(m -> pubMethods.contains(m))
				.collect(Collectors.toList());
		for (Method m : methods) {
			StringBuilder message = new StringBuilder();
			message.append(m.getName() + "(");
			AnnotatedType[] paraTypes = m.getAnnotatedParameterTypes();
			for (int i = 0; i < paraTypes.length; ++i) {
				message.append(paraTypes[i].getType().getTypeName());
				if (i < paraTypes.length - 1)
					message.append(", ");
			}
			message.append(")");
			logger.debug("found a public method " + message.toString());
		}
		List<Class<?>> decClasses = Arrays.asList(cls.getDeclaredClasses());
		List<Class<?>> pubClasses = Arrays.asList(cls.getClasses());
		List<Class<?>> classes = decClasses.stream()
				.filter(c -> pubClasses.contains(c))
				.collect(Collectors.toList());
		for (Class<?> c : classes) {
			logger.debug("found a public inner class " + c.getName());
			methods.addAll(getPublicMethods(c));
		}
		return methods;
	}
	
}
