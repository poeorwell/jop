/*
 * Created on 04.06.2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package com.jopdesign.build;

import java.util.Iterator;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.apache.bcel.util.InstructionFinder;
import com.jopdesign.tools.JopInstr;

/**
 * @author Flavius, Martin
 * 
 * TODO To change the template for this generated type comment go to Window -
 * Preferences - Java - Code Style - Code Templates
 */
public class ReplaceNativeAndCPIdx extends MyVisitor {

	// Why do we use a ConstantPoolGen and a ConstantPool?
	private ConstantPoolGen cpoolgen;

	private ConstantPool cp;

	public ReplaceNativeAndCPIdx(JOPizer jz) {
		super(jz);
	}

	public void visitJavaClass(JavaClass clazz) {

		super.visitJavaClass(clazz);

		Method[] methods = clazz.getMethods();
		cp = clazz.getConstantPool();
		cpoolgen = new ConstantPoolGen(cp);

		for (int i = 0; i < methods.length; i++) {
			if (!(methods[i].isAbstract() || methods[i].isNative())) {

				Method m = replace(methods[i]);
		        MethodInfo mi = cli.getMethodInfo(m.getName()+m.getSignature());
		        // set new method also in MethodInfo
		        mi.setMethod(m);
				if (m != null) {
					methods[i] = m;
				}
			}
		}
	}

	private Method replace(Method method) {

		MethodGen mg = new MethodGen(method, clazz.getClassName(), cpoolgen);
		InstructionList il = mg.getInstructionList();
		InstructionFinder f = new InstructionFinder(il);

		String methodId = method.getName() + method.getSignature();

		MethodInfo mi = cli.getMethodInfo(methodId);

		// find invokes first and replace call to Native by
		// JOP native instructions.
		String invokeStr = "InvokeInstruction";
		for (Iterator i = f.search(invokeStr); i.hasNext();) {
			InstructionHandle[] match = (InstructionHandle[]) i.next();
			InstructionHandle first = match[0];
			InvokeInstruction ii = (InvokeInstruction) first.getInstruction();
			if (ii.getClassName(cpoolgen).equals(JOPizer.nativeClass)) {
				short opid = (short) JopInstr.getNative(ii
						.getMethodName(cpoolgen));
				if (opid == -1) {
					System.err.println(method.getName() + ": cannot locate "
							+ ii.getMethodName(cpoolgen)
							+ ". Replacing with NOP.");
					first.setInstruction(new NOP());
				} else {
					first.setInstruction(new NativeInstruction(opid, (short) 1));
					jz.outTxt.println("\t"+first.getPosition());
					// since the new instruction is of length 1 and
					// the replaced invokespecial was of length 3
					// then we remove pc+2 and pc+1 from the MGCI info
					if (JOPizer.dumpMgci) {
						il.setPositions();
						int pc = first.getPosition();
						// important: take the high one first
						GCRTMethodInfo.removePC(pc + 2, mi);
						GCRTMethodInfo.removePC(pc + 1, mi);
					}
				}
			}

 			if (ii instanceof INVOKESPECIAL) {			    
 				// not an initializer
 				if (!ii.getMethodName(cpoolgen).equals("<init>")) {
 					// not in the same class, must be super
 					if (!cli.clazz.getClassName().equals(ii.getClassName(cpoolgen))) {
 						first.setInstruction(new JOPSYS_INVOKESUPER((short) ii.getIndex()));
 						// System.err.println("invokesuper "+ii.getClassName(cpoolgen)+"."+ii.getMethodName(cpoolgen));
 					}
 			    	}
 			}

		}

		f = new InstructionFinder(il);
		// find instructions that access the constant pool
		// and replace the index by the new value from ClassInfo
		String cpInstr = "CPInstruction";
		for (Iterator it = f.search(cpInstr); it.hasNext();) {
			InstructionHandle[] match = (InstructionHandle[]) it.next();
			InstructionHandle ih = match[0];

			CPInstruction cpii = (CPInstruction) ih.getInstruction();
			int index = cpii.getIndex();

			// we have to grab the information before we change
			// the CP index.
			FieldInstruction fi = null;
			Type ft = null;
			if (cpii instanceof FieldInstruction) {
				fi = (FieldInstruction) ih.getInstruction();
				ft = fi.getFieldType(cpoolgen);
			}

			Integer idx = new Integer(index);
			// pos is the new position in the reduced constant pool
			// idx is the position in the 'original' unresolved cpool
			int pos = cli.cpoolUsed.indexOf(idx);
			int new_index = pos + 1;
			if (pos == -1) {
				System.out.println("Error: constant " + index + " "
						+ cpoolgen.getConstant(index) + " not found");
				System.out.println("new cpool: " + cli.cpoolUsed);
				System.out.println("original cpool: " + cpoolgen);

				System.exit(-1);
			} else {
				// replace index by the offset for getfield
				// and putfield
				if (cpii instanceof GETFIELD || cpii instanceof PUTFIELD) {
					int offset = getFieldOffset(cp, index);
					// we use the offset instead of the CP index
					new_index = offset;
				}
				// set new index, position starts at
				// 1 as cp points to the length of the pool
				// System.out.println(cli.clazz.getClassName()+"."+method.getName()+"
				// "+ii+" -> "+(pos+1));
				cpii.setIndex(new_index);
			}

			// Added field instruction replacement
			// by Rasmus and extended by Martin
			// Replace reference and long/double field bytecodes
			// with 'special' bytecodes.

			if (cpii instanceof FieldInstruction) {

				boolean isRef = ft instanceof ReferenceType;
				boolean isLong = ft == BasicType.LONG || ft == BasicType.DOUBLE;

				if (fi instanceof GETSTATIC) {
					if (isRef) {
						ih.setInstruction(new GETSTATIC_REF((short) new_index));
					} else if (isLong) {
						ih
								.setInstruction(new GETSTATIC_LONG(
										(short) new_index));
					}
				} else if (fi instanceof PUTSTATIC) {
					if (isRef) {
						ih.setInstruction(new PUTSTATIC_REF((short) new_index));
					} else if (isLong) {
						ih
								.setInstruction(new PUTSTATIC_LONG(
										(short) new_index));
					}
				} else if (fi instanceof GETFIELD) {
					if (isRef) {
						ih.setInstruction(new GETFIELD_REF((short) new_index));
					} else if (isLong) {
						ih.setInstruction(new GETFIELD_LONG((short) new_index));
					}
				} else if (fi instanceof PUTFIELD) {
					if (isRef) {
						ih.setInstruction(new PUTFIELD_REF((short) new_index));
					} else if (isLong) {
						ih.setInstruction(new PUTFIELD_LONG((short) new_index));
					}
				}
			}
		}

		Method m = mg.getMethod();
		il.dispose();
		return m;

	}

	private int getFieldOffset(ConstantPool cp, int index) {

		// from ClassInfo.resolveCPool

		Constant co = cp.getConstant(index);

		int fidx = ((ConstantFieldref) co).getClassIndex();
		ConstantClass fcl = (ConstantClass) cp.getConstant(fidx);
		String fclname = fcl.getBytes(cp).replace('/', '.');
		// got the class name
		int sigidx = ((ConstantFieldref) co).getNameAndTypeIndex();
		ConstantNameAndType signt = (ConstantNameAndType) cp
				.getConstant(sigidx);
		String sigstr = signt.getName(cp) + signt.getSignature(cp);
		ClassInfo clinf = (ClassInfo) ClassInfo.mapClassNames.get(fclname);
		int j;
		boolean found = false;
		while (!found) {
			for (j = 0; j < clinf.clft.len; ++j) {
				if (clinf.clft.key[j].equals(sigstr)) {
					found = true;
					// should not happen - check it for sure
					if (clinf.clft.isStatic[j]) {
						System.out.println("Error is static in ReplNCI");
						System.exit(-1);
					}
					return clinf.clft.idx[j];
				}
			}
			if (!found) {
				clinf = clinf.superClass;
				if (clinf == null) {
					System.out.println("Error: field " + fclname + "." + sigstr
							+ " not found!");
					break;
				}
			}
		}
		System.out.println("Error in getFieldOffset()");
		System.exit(-1);
		return 0;
	}

	class GETSTATIC_REF extends FieldInstruction {
		public GETSTATIC_REF(short index) {
			super((short) JopInstr.get("getstatic_ref"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class PUTSTATIC_REF extends FieldInstruction {
		public PUTSTATIC_REF(short index) {
			super((short) JopInstr.get("putstatic_ref"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class GETFIELD_REF extends FieldInstruction {
		public GETFIELD_REF(short index) {
			super((short) JopInstr.get("getfield_ref"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class PUTFIELD_REF extends FieldInstruction {
		public PUTFIELD_REF(short index) {
			super((short) JopInstr.get("putfield_ref"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class GETSTATIC_LONG extends FieldInstruction {
		public GETSTATIC_LONG(short index) {
			super((short) JopInstr.get("getstatic_long"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class PUTSTATIC_LONG extends FieldInstruction {
		public PUTSTATIC_LONG(short index) {
			super((short) JopInstr.get("putstatic_long"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class GETFIELD_LONG extends FieldInstruction {
		public GETFIELD_LONG(short index) {
			super((short) JopInstr.get("getfield_long"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class PUTFIELD_LONG extends FieldInstruction {
		public PUTFIELD_LONG(short index) {
			super((short) JopInstr.get("putfield_long"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class NativeInstruction extends Instruction {
		public NativeInstruction(short arg0, short arg1) {
			super(arg0, arg1);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}
	}

	class JOPSYS_INVOKESUPER extends InvokeInstruction {
		public JOPSYS_INVOKESUPER(short index) {
			super((short) JopInstr.get("invokesuper"), index);
		}

		public void accept(org.apache.bcel.generic.Visitor v) {
		}

		// could be copied from INVOKESPECIAL
		public Class[] getExceptions() {
		       return null;
		}
	}
}
