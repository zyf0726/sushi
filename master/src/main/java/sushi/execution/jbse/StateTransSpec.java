package sushi.execution.jbse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import heapSyn.jbse.val.PrimitiveSymbolic;
import heapsyn.common.exceptions.UnhandledJBSEPrimitive;
import heapsyn.common.exceptions.UnhandledJBSEValue;
import heapsyn.common.exceptions.UnsupportedPrimitiveType;
import heapsyn.common.exceptions.UnsupportedSMTOperator;
import heapsyn.heap.ClassH;
import heapsyn.heap.FieldH;
import heapsyn.heap.ObjectH;
import heapsyn.heap.SymbolicHeapAsDigraph;
import heapsyn.smtlib.ApplyExpr;
import heapsyn.smtlib.BoolConst;
import heapsyn.smtlib.BoolVar;
import heapsyn.smtlib.ExistExpr;
import heapsyn.smtlib.IntConst;
import heapsyn.smtlib.IntVar;
import heapsyn.smtlib.SMTExpression;
import heapsyn.smtlib.SMTOperator;
import heapsyn.wrapper.symbolic.Specification;
import static heapsyn.wrapper.symbolic.JBSEHeapTransformer.BLANK_OBJ;
import jbse.common.exc.UnexpectedInternalException;
import jbse.mem.Clause;
import jbse.mem.ClauseAssume;
import jbse.mem.ClauseAssumeAliases;
import jbse.mem.ClauseAssumeExpands;
import jbse.mem.ClauseAssumeNull;
import jbse.mem.ClauseAssumeReferenceSymbolic;
import jbse.mem.HeapObjekt;
import jbse.mem.Objekt;
import jbse.mem.ObjektImpl;
import jbse.mem.PathCondition;
import jbse.mem.ReachableObjectsCollector;
import jbse.mem.State;
import jbse.mem.Variable;
import jbse.mem.exc.FrozenStateException;
import jbse.val.Expression;
import jbse.val.Operator;
import jbse.val.Primitive;
import jbse.val.PrimitiveSymbolicLocalVariable;
import jbse.val.PrimitiveSymbolicMemberField;
import jbse.val.Reference;
import jbse.val.ReferenceConcrete;
import jbse.val.ReferenceSymbolic;
import jbse.val.ReferenceSymbolicLocalVariable;
import jbse.val.ReferenceSymbolicMemberField;
import jbse.val.Simplex;
import jbse.val.Value;
import jbse.val.WideningConversion;
import sushi.exceptions.UnhandledInternalException;

public class StateTransSpec {
	
//	private Map<HeapObjekt, ObjectH> finjbseObjMap = new HashMap<>();
//	private Map<Primitive, ObjectH> finjbseVarMap = new HashMap<>();
//	private Map<ObjectH,Primitive> finVarjbseMap = new HashMap<>(); 
	private Map<ReferenceSymbolic,ObjectH> refObjMap=new HashMap<>();
	private Map<ObjectH,HashMap<FieldH, ObjectH>> fieldMaps=new HashMap<>();
	private TreeMap<Long,HeapObjekt> objects;
	private Map<PrimitiveSymbolicLocalVariable,ObjectH> primmap;
	private Map<ReferenceSymbolic,ObjectH> ObjMap=new HashMap<>();
	private Set<ObjectH> conObj=new HashSet<>();
	private PathCondition pd;
	private State state;
	
	public ObjectH[] args; 
	
//	public Map<HeapObjekt, ObjectH> getfinjbseObjMap() {
//		return this.finjbseObjMap;
//	}
//	
//	public Map<Primitive, ObjectH> getfinjbseVarMap() {
//		return this.finjbseVarMap;
//	}
//	
//	public Map<ObjectH,Primitive> getfinVarjbseMap() {
//		return this.finVarjbseMap;
//	}
	
	public TreeMap<Long,HeapObjekt> getobjects() {
		return this.objects;
	}
	
	private static Map<Operator,SMTOperator> opMap=new HashMap<>();
	
	static {
		opMap.put(Operator.ADD, SMTOperator.ADD);
		opMap.put(Operator.SUB, SMTOperator.SUB);
		opMap.put(Operator.AND, SMTOperator.AND);
		opMap.put(Operator.OR, SMTOperator.OR);
		opMap.put(Operator.EQ, SMTOperator.BIN_EQ);
		opMap.put(Operator.NE, SMTOperator.BIN_NE);
		opMap.put(Operator.MUL, SMTOperator.MUL);
		opMap.put(Operator.NOT, SMTOperator.UN_NOT);
		opMap.put(Operator.LE, SMTOperator.BIN_LE);
		opMap.put(Operator.LT, SMTOperator.BIN_LT);
		opMap.put(Operator.GE, SMTOperator.BIN_GE);
		opMap.put(Operator.GT, SMTOperator.BIN_GT);
		opMap.put(Operator.NEG, SMTOperator.UN_MINUS);
		
	}
	
//	// transform a HeapObjekt to an ObjectH (with fieldValueMap undetermined)
//	private static ObjectH transHeapObjektToObjectH(ObjektImpl o) {
//		try {
//			String clsName = o.getType().getClassName().replace('/','.');
//			Class<?> javaClass = Class.forName(clsName);
//			return new ObjectH(ClassH.of(javaClass), null);
//		} catch (ClassNotFoundException e) {
//			// this should never happen
//			throw new UnexpectedInternalException(e);
//		}
//	}
	
	private ObjectH transRefToObjectH(ReferenceSymbolic ref) {
		try {
			HeapObjekt ok=null;
			try {
				ok = this.state.getObject(ref);
			} catch (FrozenStateException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
				throw new UnhandledInternalException(e);
			}
			String clsName = ok.getType().getClassName().replace('/','.');
			//System.out.println(clsName);
			//clsName=clsName.substring(1, clsName.length()-1);
			Class<?> javaClass = Class.forName(clsName);
			ObjectH obj=new ObjectH(ClassH.of(javaClass), null);
			this.refObjMap.put(ref,obj);
			HashMap<FieldH, ObjectH> fieldMap=new HashMap<>();
			List<Field> fieldList = new ArrayList<>() ;
			Class<?> tempClass = javaClass;
			while (tempClass != null) {//当父类为null的时候说明到达了最上层的父类(Object类).
			      fieldList.addAll(Arrays.asList(tempClass .getDeclaredFields()));
			      tempClass = tempClass.getSuperclass(); //得到父类,然后赋给自己
			}
			//Field[] fields = javaClass.getFields();
			for (Variable var : ok.fields().values()) {
				if(var.getName().charAt(0)=='_'|| var.getName().equals("modCount")) continue; // || var.getName().equals("modCount")
				Field javaField=null;
				try {
					for(Field f:fieldList) {
						if(f.getName().equals(var.getName())) {
							javaField=f;
							break;
						}
					}
					if(javaField==null) throw new NoSuchFieldException();
				} catch (NoSuchFieldException e1) {
					// TODO Auto-generated catch block
					throw new UnhandledInternalException(e1);
					// System.out.println(clsName);
					// System.out.println(var.getName());
					// e1.printStackTrace();
				}
				FieldH field = FieldH.of(javaField);
				Value varValue = var.getValue();
				if(varValue instanceof ReferenceConcrete) {
					ReferenceConcrete rc = (ReferenceConcrete) varValue;
					HeapObjekt objekt=null;
					try {
						objekt = state.getObject(rc);
					} catch (FrozenStateException e) {
						// TODO Auto-generated catch block
						// e.printStackTrace();
						throw new UnhandledInternalException(e);
					}
					if(objekt!=null&&objekt.getType().getClassName().equals(Object.class.getName())) {
						ObjectH value=new ObjectH(ClassH.of(Object.class),new HashMap<FieldH, ObjectH>());
						fieldMap.put(field, value);
						this.conObj.add(value);
					}
					else fieldMap.put(field, BLANK_OBJ);
				}
				else if(varValue instanceof ReferenceSymbolicMemberField) {
					ReferenceSymbolicMemberField rs = (ReferenceSymbolicMemberField) varValue;
					if(rs.getContainer()==ref) {
						ObjectH value = null;
						if(state.resolved(rs)&&rs.getStaticType().equals("Ljava/lang/Object;")) {
							value=this.ObjMap.get(rs);
							fieldMap.put(field, value);
						}
						else fieldMap.put(field, BLANK_OBJ);
					}
					else fieldMap.put(field, BLANK_OBJ);
				}
				else if(varValue instanceof Primitive) {
					ObjectH value=null; 
					if(varValue.getType()=='I') value = new ObjectH(new IntVar());
					else if(varValue.getType()=='Z') value=new ObjectH(new BoolVar());
					fieldMap.put(field, value);
				}
				
			}
			this.fieldMaps.put(obj, fieldMap);
			return obj;
		} catch (ClassNotFoundException | SecurityException e) {
			// this should never happen
			throw new UnexpectedInternalException(e);
		}
	}
	
	
//	public void transform(State state) throws FrozenStateException {		
//		//Heap heap=state.__getHeap();
//		PathCondition pathCond=state.__getPathCondition();
//		this.pd=pathCond;
//		
//		//Heap delHeap = filterPreObjekt(heap);
//		final Set<Long> reachable;
//		reachable= new ReachableObjectsCollector().reachable(state, false);
//
//
//		Set<Map.Entry<Long, Objekt>> entries = state.getHeap().entrySet().stream()
//		        .filter(e -> reachable.contains(e.getKey()))
//		        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue(), throwingMerger(), TreeMap::new)).entrySet();
//
//		//Map<Long, HeapObjekt> objekts = delHeap.__getObjects();
//		Map<Long,HeapObjekt> objekts=new HashMap<>();
//		for(Entry<Long,Objekt> entry: entries) {
//			objekts.put(entry.getKey(), (HeapObjekt) entry.getValue());
//		}
//		this.objects=new TreeMap<>(objekts);
//		
//		//Map<HeapObjekt, ObjectH> finjbseObjMap = new HashMap<>();
//		
//		for (HeapObjekt o : objekts.values()) {
//			this.finjbseObjMap.put(o, transHeapObjektToObjectH((ObjektImpl) o));
//		}
//		
//		for (Entry<HeapObjekt, ObjectH> entry : this.finjbseObjMap.entrySet()) {
//			// determine fieldValMap for each ObjectH
//			HeapObjekt ok = entry.getKey();
//			ObjectH oh = entry.getValue();
//			Map<FieldH, ObjectH> fieldValMap = new HashMap<>();
//			for (Variable var : ok.fields().values()) {
//				FieldH field = null;
//				try {
//					String clsName = ok.getType().getClassName().replace('/', '.');
//					Class<?> javaClass = Class.forName(clsName);
//					Field javaField = javaClass.getDeclaredField(var.getName());
//					field = FieldH.of(javaField);
//				} catch (NoSuchFieldException | SecurityException | ClassNotFoundException e) {
//					// this should never happen
//					throw new UnexpectedInternalException(e);
//				}
//				Value varValue = var.getValue();
//				if (varValue instanceof ReferenceConcrete) {
//					ReferenceConcrete rc = (ReferenceConcrete) varValue;
//					HeapObjekt objekt = objekts.get(rc.getHeapPosition());
//					ObjectH value = this.finjbseObjMap.get(objekt);
//					if (value == null) {
//						fieldValMap.put(field, ObjectH.NULL);
//					} else {
//						fieldValMap.put(field, value);
//					}
//				} else if (varValue instanceof ReferenceSymbolic) {
//					ReferenceSymbolic ref = (ReferenceSymbolic) varValue;
//					ObjectH value = null;
//				 	if (pathCond.resolved(ref)) {
//				 		Long pos = pathCond.getResolution(ref);
//				 		if (pos == jbse.mem.Util.POS_NULL) {
//				 			value = ObjectH.NULL;
//				 		} else {
//				 			value = this.finjbseObjMap.get(objekts.get(pos));
//				 		}
//				 	} else {
//				 		value = ObjectH.NULL;
//				 	}
//				 	fieldValMap.put(field, value);
//				} else if (varValue instanceof Primitive) {
//					ObjectH value=null; 
//					if(varValue.getType()=='I') value = new ObjectH(new IntVar());
//					else if(varValue.getType()=='Z') value=new ObjectH(new BoolVar());
//					fieldValMap.put(field, value);
//					this.finjbseVarMap.put((Primitive) varValue, value);
//					this.finVarjbseMap.put(value,(Primitive) varValue);
//				} 
//				else {
//					throw new UnhandledJBSEValue(varValue.getClass().getName()); 
//				}
//			}
//			oh.setFieldValueMap(fieldValMap);
//		}
//						
//	}
	
	private SMTExpression getObjcond(List<Clause> clauses) {
		ArrayList<SMTExpression> pds=new ArrayList<>();
		ArrayList<heapsyn.smtlib.Variable> vars=new ArrayList<>();
		for(int i=0;i<clauses.size();++i) {
			ClauseAssumeReferenceSymbolic ca =(ClauseAssumeReferenceSymbolic) clauses.get(i);
			ReferenceSymbolic ref=ca.getReference();
			ObjectH obj=new ObjectH(ClassH.of(Object.class),new HashMap<FieldH, ObjectH>());
			this.ObjMap.put(ref, obj);
			if(ca instanceof ClauseAssumeNull) {
				pds.add(new ApplyExpr(SMTOperator.BIN_EQ,obj.getVariable(),new IntConst(0)));
			}
			else if(ca instanceof ClauseAssumeAliases) {
				HeapObjekt heapObj=((ClauseAssumeAliases) ca).getObjekt();
				ReferenceSymbolic oriref=heapObj.getOrigin();
				ObjectH oriObj=this.ObjMap.get(oriref);
				if(oriObj==null) {
					return null;
					//throw new UnexpectedInternalException("Object misses reference");
				}
				pds.add(new ApplyExpr(SMTOperator.BIN_EQ,obj.getVariable(),oriObj.getVariable()));
			}
			else {
				pds.add(new ApplyExpr(SMTOperator.AND, new ApplyExpr(SMTOperator.BIN_NE,obj.getVariable(),new IntConst(0)),
						forall(SMTOperator.BIN_NE,SMTOperator.AND,obj.getVariable(),vars)));
			}
			vars.add(obj.getVariable());
		}
		if(pds.isEmpty()) return new BoolConst(true);
		return new ApplyExpr(SMTOperator.AND,pds);
	}
	
	private SMTExpression JBSEexpr2SMTexpr(Primitive p) {
		if(p instanceof Simplex) {
			Simplex s=(Simplex) p;
			if(s.getType()=='I') return new IntConst((Integer)s.getActualValue());
			else if(s.getType()=='Z') return new BoolConst((Boolean)s.getActualValue());
			else throw new UnsupportedPrimitiveType();
		}
		else if(p instanceof PrimitiveSymbolicLocalVariable) {
			if(this.primmap.containsKey(p)) return this.primmap.get(p).getVariable();
			if(p.getType()=='I') {
				IntVar intV=new IntVar();
				this.primmap.put((PrimitiveSymbolicLocalVariable) p, new ObjectH(intV));
				return intV;
			}
			else if(p.getType()=='Z'){
				BoolVar boolV=new BoolVar();
				this.primmap.put((PrimitiveSymbolicLocalVariable) p, new ObjectH(boolV));
				return boolV;
			}
		}
		else if(p instanceof PrimitiveSymbolicMemberField) {
			PrimitiveSymbolicMemberField memp=(PrimitiveSymbolicMemberField) p;
			ReferenceSymbolic ref=memp.getContainer();
			String fieldName=memp.getFieldName();
			ObjectH obj=this.refObjMap.get(ref);
			for(FieldH field:obj.getFields()) {
				if(field.getName().equals(fieldName)) 
					return obj.getFieldValue(field).getVariable();
			}
			
		}
		else if(p instanceof Expression) {
			Expression expr=(Expression) p;
			Primitive fst=expr.getFirstOperand();
			Primitive snd=expr.getSecondOperand();
			Operator op=expr.getOperator();
			SMTOperator smtop=opMap.get(op);
			if(smtop==null) {
				System.out.println(this.state.getBranchIdentifier());
				System.out.println(p.toString());
				throw new UnsupportedSMTOperator(op.toString());
			}
			if(fst instanceof WideningConversion || snd instanceof WideningConversion) { //only boolean to int
				assert(smtop==SMTOperator.BIN_EQ||smtop==SMTOperator.BIN_NE);
				if(fst instanceof WideningConversion) {
					assert(snd instanceof Simplex&&snd.getType()=='I');
					Integer n=(Integer) ((Simplex) snd).getActualValue();
					assert(n==0||n==1);
					Primitive ori=((WideningConversion) fst).getArg();
					assert(ori.getType()=='Z');
					return new ApplyExpr(smtop,JBSEexpr2SMTexpr(ori),new BoolConst(n==1));
				}
				else if(snd instanceof WideningConversion) {
					assert(fst instanceof Simplex&&fst.getType()=='I');
					Integer n=(Integer) ((Simplex) fst).getActualValue();
					assert(n==0||n==1);
					Primitive ori=((WideningConversion) snd).getArg();
					assert(ori.getType()=='Z');
					return new ApplyExpr(smtop,JBSEexpr2SMTexpr(ori),new BoolConst(n==1));
				}
			}
			if(expr.isUnary()) {
				return new ApplyExpr(smtop,JBSEexpr2SMTexpr(snd));
			}
			else {
				return new ApplyExpr(smtop,JBSEexpr2SMTexpr(fst),JBSEexpr2SMTexpr(snd));
			}
		} else {
			throw new UnhandledJBSEPrimitive(p.getClass().getName());
		}
		return new BoolConst(true);
	}
	
	private boolean toIgnore(Primitive p) {
		if(p instanceof PrimitiveSymbolicLocalVariable) {
			return false;
		}
		if(p instanceof PrimitiveSymbolicMemberField) {
			PrimitiveSymbolicMemberField pfield=(PrimitiveSymbolicMemberField)p;
			return pfield.getFieldName().charAt(0)=='_' | pfield.getFieldName().equals("modCount"); //| pfield.getFieldName().equals("modCount")
		}
		else if(p instanceof Simplex) {
			return false;
		}
		else if(p instanceof Expression) {
			Expression expr=(Expression) p;
			Primitive fst=expr.getFirstOperand();
			Primitive snd=expr.getSecondOperand();
			if(expr.isUnary()) {
				return toIgnore(snd);
			}
			else {
				return toIgnore(fst)||toIgnore(snd);
			}
		}
		else if(p instanceof WideningConversion) {
			return toIgnore(((WideningConversion)p).getArg());
		}
		else {
			throw new UnhandledJBSEPrimitive(p.getClass().getName());
		}
	}
	
	private boolean isObj(ObjectH obj) {
		return obj.getClassH().getJavaClass()==Object.class;
	}
	
	private SMTExpression forall(SMTOperator eq,SMTOperator ao,heapsyn.smtlib.Variable intv,ArrayList<heapsyn.smtlib.Variable> intvs) {
		if(intvs.isEmpty()) {
			if(eq==SMTOperator.BIN_EQ) return new BoolConst(false);
			else if(eq==SMTOperator.BIN_NE) return new BoolConst(true);
		}
		ArrayList<ApplyExpr> ret=new ArrayList<>();
		for(heapsyn.smtlib.Variable v:intvs) {
			ret.add(new ApplyExpr(eq,intv,v));
		}
		//if(ret.isEmpty()) return new BoolConst(true);
		return new ApplyExpr(ao,ret);
	}
	
	private SMTExpression getCond(List<Clause> clauses) {
		ArrayList<SMTExpression> pds=new ArrayList<>();
		for(int i=0;i<clauses.size();++i) {
			ClauseAssume ca =(ClauseAssume) clauses.get(i);
			pds.add(this.JBSEexpr2SMTexpr(ca.getCondition()));
		}
		if(pds.size()==0) return new BoolConst(true);
//		else {
//			ApplyExpr ret=(ApplyExpr)pds.get(0);
//			for(int i=1;i<pds.size();++i) {
//				ret=new ApplyExpr(SMTOperator.AND,ret,(ApplyExpr)pds.get(i));
//			}
//			return ret;
//		}
		else return new ApplyExpr(SMTOperator.AND,pds);
	}
	
	private SymbolicHeapAsDigraph genHeap(List<Clause> refclause) {
		for(Clause clause:refclause) {
			ClauseAssumeReferenceSymbolic refcls=(ClauseAssumeReferenceSymbolic) clause;
			if(refcls instanceof ClauseAssumeExpands) {
				ReferenceSymbolic ref=refcls.getReference();
				if(!this.refObjMap.containsKey(ref)) {
					this.transRefToObjectH(ref);
				}
			}
		}
		
		for(Clause clause:refclause) {
			ClauseAssumeReferenceSymbolic refcls=(ClauseAssumeReferenceSymbolic) clause;
			ReferenceSymbolic ref=refcls.getReference();
			if(refcls instanceof ClauseAssumeAliases) {
				ClauseAssumeAliases alsclause=(ClauseAssumeAliases) refcls;
				ReferenceSymbolic alsref=alsclause.getObjekt().getOrigin();
				this.refObjMap.put(ref, this.refObjMap.get(alsref));
			}
		}
		
		for(Clause clause:refclause) {
			ClauseAssumeReferenceSymbolic refcls=(ClauseAssumeReferenceSymbolic) clause;
			ReferenceSymbolic ref=refcls.getReference();
			if(ref instanceof ReferenceSymbolicMemberField) {
				ReferenceSymbolicMemberField memberref=(ReferenceSymbolicMemberField) ref;
				String fieldname=memberref.getFieldName();
				ReferenceSymbolic container=memberref.getContainer();
				ObjectH obj=ObjectH.NULL;
				if(!(refcls instanceof ClauseAssumeNull))
					obj=this.refObjMap.get(ref);
				ObjectH objcontainer=this.refObjMap.get(container);
				for(FieldH field:this.fieldMaps.get(objcontainer).keySet()) {
					if(field.getName().equals(fieldname)) {
						this.fieldMaps.get(objcontainer).put(field, obj);
						break;
					}
				}
			}
		}
		
		
		HashSet<ObjectH> objs=new HashSet<>(this.refObjMap.values());
		for(ObjectH obj:objs) {
			HashMap<FieldH, ObjectH> fieldMap=this.fieldMaps.get(obj);
			HashMap<FieldH, ObjectH> finfieldMap=new HashMap<FieldH, ObjectH>();
			for(Entry<FieldH,ObjectH> entry:fieldMap.entrySet()) {
				if(entry.getValue()!=BLANK_OBJ) finfieldMap.put(entry.getKey(), entry.getValue());
			}
			obj.setFieldValueMap(finfieldMap);
		}
		
		HashSet<ObjectH> accobjs=new HashSet<>();
		for(Entry<ReferenceSymbolic, ObjectH> entry:this.refObjMap.entrySet()) {
			if(entry.getKey() instanceof ReferenceSymbolicLocalVariable)
				accobjs.add(entry.getValue());
		}
		
		for(Entry<ReferenceSymbolic, ObjectH> entry:this.ObjMap.entrySet()) {
			if(entry.getKey() instanceof ReferenceSymbolicLocalVariable)
				accobjs.add(entry.getValue());
		}
		
		accobjs.add(ObjectH.NULL);
		return new SymbolicHeapAsDigraph(accobjs,null);
	}

	public Specification genSpec(State state) {		
		Specification spec=new Specification();
		
		this.state=state;
		ArrayList<Clause> refclause=new ArrayList<>(); // clauses about reference
		ArrayList<Clause> primclause=new ArrayList<>(); // clauses about primitive
		ArrayList<Clause> objclause=new ArrayList<>();
		
		PathCondition jbsepd=state.getRawPathCondition();
		List<Clause> clauses=jbsepd.getClauses();
		for(int i=0;i<clauses.size();++i) {
			Clause clause=clauses.get(i);
			if(clause instanceof ClauseAssume) {
				if(!toIgnore(((ClauseAssume)clause).getCondition())) primclause.add(clause);
			}
			else if(clause instanceof ClauseAssumeReferenceSymbolic) {
				ReferenceSymbolic ref=((ClauseAssumeReferenceSymbolic)clause).getReference();
				if(ref instanceof ReferenceSymbolicMemberField) {
					ReferenceSymbolicMemberField memberref=(ReferenceSymbolicMemberField) ref;
					String fieldname=memberref.getFieldName();
					if(fieldname.charAt(0)=='_' || fieldname.equals("modCount")) continue; //|| fieldname.equals("modCount")
				}
				if(ref.getStaticType().equals("Ljava/lang/Object;")) {
					objclause.add(clause);
					continue;
				}
				refclause.add(clause);
			}
		}
		
		this.primmap=new HashMap<>();
		
		SMTExpression Objcond=this.getObjcond(objclause);
		if(Objcond==null) return null;
		
		spec.expcHeap=this.genHeap(refclause);
		spec.condition=new ApplyExpr(SMTOperator.AND, this.getCond(primclause),Objcond);
		
		Value[] vargs = state.getValueArgs();
		this.args=new ObjectH[vargs.length];
		for(int i=0;i<this.args.length;++i) {
			if(vargs[i] instanceof ReferenceSymbolic) {
				ReferenceSymbolic ref=(ReferenceSymbolic)vargs[i];
				this.args[i]=this.ObjMap.get(ref);
				if(this.args[i]==null) this.args[i]= this.refObjMap.get(ref);
				if(this.args[i]==null) this.args[i]=ObjectH.NULL;
			}
			else if(vargs[i] instanceof PrimitiveSymbolicLocalVariable) {
				PrimitiveSymbolicLocalVariable prim=(PrimitiveSymbolicLocalVariable)vargs[i];
				this.args[i]=this.primmap.get(prim);
				if(this.args[i]==null) {
					if(prim.getType()=='I') this.args[i]=new ObjectH(new IntVar());
					else if(prim.getType()=='Z') this.args[i]=new ObjectH(new BoolVar());
					else {
						throw new UnhandledJBSEPrimitive(prim.getClass().getName());
					}
				}
			}
			else {
				throw new UnhandledJBSEValue(vargs[i].getClass().getName()); 
			}
		}
			
		
		return spec;
	}
	
}
