package sushi.execution.evosuite;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import heapsyn.algo.Statement;
import heapsyn.algo.TestGenerator;
import heapsyn.algo.WrappedHeap;
import heapsyn.heap.ObjectH;
import heapsyn.wrapper.symbolic.Specification;
import sushi.logging.Logger;

public abstract class RunHeapSyn {
	
	private static final Logger logger = new Logger(RunHeapSyn.class);
	
	private TestGenerator testGenerator;
	
	private long timeBuildGraph;
	private long totTimeFail, totTimeSucc;
	private int numRunFail, numRunSucc;
	
	public RunHeapSyn() {
		this.totTimeFail = this.totTimeSucc = 0;
		this.numRunFail = this.numRunSucc = 0;
		logger.debug("building heap transformation graph");
		long start = System.currentTimeMillis();
		List<WrappedHeap> heaps = this.buildGraph();
		long elapsed = System.currentTimeMillis() - start;
		logger.debug("heap transformation graph built, elapsed " + elapsed/1000 + " seconds");
		this.testGenerator = new TestGenerator(heaps);
		this.timeBuildGraph = elapsed;
	}
	
	abstract protected List<WrappedHeap> buildGraph();
	
	public final boolean useEvosuiteIfFailed() {
		return false;
	}
	
	public synchronized final List<Statement> generateTest(Specification spec, ObjectH... args) {
		long start = System.currentTimeMillis();
		List<Statement> stmts = testGenerator.generateTestWithSpec(spec, args);
		long elapsed = System.currentTimeMillis() - start;
		if (stmts == null) { // failed
			numRunFail += 1;
			totTimeFail += elapsed;
			logger.debug("generate failed, elapsed " + elapsed + " milliseconds");
		} else { // successful
			numRunSucc += 1;
			totTimeSucc += elapsed;
			logger.debug("generate successfully, elpased " + elapsed + " milliseconds");
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
			return -1.0;
		}
	}
	
	public final double getAvgSuccTime() {
		if (this.numRunSucc != 0) {
			return (double) this.totTimeSucc / this.numRunSucc;
		} else {
			return -1.0;
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
	
	protected static List<Method> getPublicMethods(String clsName)
			throws NoSuchMethodException, ClassNotFoundException {
		Class<?> cls = Class.forName(clsName);
		List<Method> decMethods = Arrays.asList(cls.getDeclaredMethods());
		List<Method> pubMethods = Arrays.asList(cls.getMethods());
		List<Method> methods = decMethods.stream()
				.filter(m -> pubMethods.contains(m))
				.collect(Collectors.toList());
		System.out.println("public methods (" + clsName + "):");
		for (Method m : methods) {
			System.out.print("  " + m.getName() + "(");
			AnnotatedType[] paraTypes = m.getAnnotatedParameterTypes();
			for (int i = 0; i < paraTypes.length; ++i) {
				System.out.print(paraTypes[i].getType().getTypeName());
				if (i < paraTypes.length - 1)
					System.out.print(", ");
			}
			System.out.println(")");
		}
		return methods;
	}
	
}
