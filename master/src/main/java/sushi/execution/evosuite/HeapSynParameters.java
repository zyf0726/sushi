package sushi.execution.evosuite;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import heapsyn.common.settings.JBSEParameters;
import heapsyn.common.settings.Options;

public class HeapSynParameters {
	
	public static Options heapsynOptions = Options.I();
	public static JBSEParameters jbseParams = JBSEParameters.I();
	
	private Class<?> targetClass;
	
	public void setTargetClass(Class<?> targetClass) {
		this.targetClass = targetClass;
	}
	
	public Class<?> getTargetClass() {
		return this.targetClass;
	}
	
	private int maxSeqLength;
	
	public void setMaxSeqLength(int maxLength) {
		this.maxSeqLength = maxLength;
	}
	
	public int getMaxSeqLength() {
		return this.maxSeqLength;
	}
	
	private Map<Class<?>, Integer> heapScope = new HashMap<>();
	
	public void clearHeapScope() {
		this.heapScope.clear();
	}
	
	public void setHeapScope(Class<?> cls, int scope) {
		this.heapScope.put(cls, scope);
	}
	
	public Map<Class<?>, Integer> getHeapScope() {
		return this.heapScope;
	}
	
	private Predicate<String> fieldFilter;
	
	public void setFieldFilter(Predicate<String> fieldFilter) {
		this.fieldFilter = fieldFilter;
	}
	
	public Predicate<String> getFieldFilter() {
		return this.fieldFilter;
	}

}
