/*
 * BridJ - Dynamic and blazing-fast native interop for Java.
 * http://bridj.googlecode.com/
 *
 * Copyright (c) 2010-2015, Olivier Chafik (http://ochafik.com/)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Olivier Chafik nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY OLIVIER CHAFIK AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.bridj;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import org.bridj.CallIO.NativeObjectHandler;
import org.bridj.util.*;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Member;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.nio.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.*;
import org.bridj.ann.Virtual;
import org.bridj.ann.Array;
import org.bridj.ann.Union;
import org.bridj.ann.Bits;
import org.bridj.ann.Field;
import org.bridj.ann.Struct;
import org.bridj.ann.Alignment;
import static org.bridj.Pointer.*;
import static org.bridj.StructUtils.*;

/**
 * Object responsible for reading and writing of a C struct's fields.<br>
 * End-users should not use this class, it's used by runtimes.<br>
 * See {@link org.bridj.StructDescription}.
 * @author ochafik
 */
public class StructIO {

    static Map<Type, StructIO> structIOs = new HashMap<Type, StructIO>();

    public static StructIO getInstance(Type structType) {
        return getInstance(Utils.getClass(structType), structType);
    }
    public static StructIO getInstance(Class structClass, Type structType) {
    	synchronized (structIOs) {
            StructIO io = structIOs.get(structType == null ? structClass : structType);
            if (io == null) {
            	io = new StructIO(structClass, structType);
            	if (io != null)
            		registerStructIO(structClass, structType, io);
            }
            return (StructIO)io;
        }
    }

    public static synchronized StructIO registerStructIO(Class structClass, Type structType, StructIO io) {
        structIOs.put(structType, io);
        return io;
    }

    protected PointerIO<?> pointerIO;
    public final StructDescription desc;
    
	public StructIO(Class<?> structClass, Type structType) {
		this.desc = new StructDescription(structClass, structType, StructCustomizer.getInstance(structClass));
        // Don't call build here, for recursive initialization cases (TODO test this)
	}
	
	@Override
	public String toString() {
		return "StructIO(" + desc + ")";
	}
	
	public boolean equal(StructObject a, StructObject b) {
		return compare(a, b) == 0;	
	}
	public int compare(StructObject a, StructObject b) {
		return StructUtils.compare(a, b, desc.getSolidRanges());
	}
	
	public final String describe(StructObject struct) {
		return desc.describe(struct);
	}
	
	/**
	 * Write struct fields implemented as Java fields to the corresponding native memory (Java fields are written to native memory).<br>
	 * This does not concern normal structs as generated by JNAerator (which use getters and setters methods that read and write the fields directly from / to the native memory), but rather structs that are in the JNA style.
	 */
	public final void writeFieldsToNative(StructObject struct) {
		desc.build();
		if (!desc.hasFieldFields)
			return;
		try {
			for (StructFieldDescription fd : desc.fields) {
				if (fd.field == null)
					continue;
			
				if (fd.isArray)
					continue;

				Object value = fd.field.get(struct);
				if (value instanceof NativeObject) {//fd.isNativeObject) {
						if (value != null) 
							BridJ.writeToNative((NativeObject)value);
						continue;
				}
				Pointer ptr = struct.peer.offset(fd.byteOffset);
				Type tpe = fd.isNativeObject || fd.isArray ? fd.nativeTypeOrPointerTargetType : fd.field.getGenericType();
				ptr = ptr.as(tpe);
				ptr = fixIntegralTypeIOToMatchLength(ptr, fd.byteLength, fd.arrayLength);
				
				if (fd.isCLong && CLong.SIZE == 4 || fd.isSizeT && SizeT.SIZE == 4)
					value = (int)(long)(Long)value;
				
				ptr.set(value);
			}
		} catch (Throwable th) {
			throw new RuntimeException("Unexpected error while writing fields from struct " + Utils.toString(desc.structType) + " (" + getPointer(struct) + ")", th);
		}
	}
	
	/**
	 * Read struct fields implemented as Java fields from the corresponding native memory (Java fields are read from native memory).<br>
	 * This does not concern normal structs as generated by JNAerator (which use getters and setters methods that read and write the fields directly from / to the native memory), but rather structs that are in the JNA style.
	 */
	public final void readFieldsFromNative(StructObject struct) {
		desc.build();
		if (!desc.hasFieldFields)
			return;
		try {
			for (StructFieldDescription fd : desc.fields) {
				if (fd.field == null)
					continue;

				Pointer ptr = struct.peer.offset(fd.byteOffset);
				Type tpe = fd.isNativeObject || fd.isArray ? fd.nativeTypeOrPointerTargetType : fd.field.getGenericType();
				ptr = ptr.as(tpe);
				ptr = fixIntegralTypeIOToMatchLength(ptr, fd.byteLength, fd.arrayLength);
				Object value;
				if (fd.isArray) {
					ptr = ptr.validElements(fd.arrayLength);
					value = ptr;
				} else {
					value = ptr.get();
				}
				fd.field.set(struct, value);
				
				if (value instanceof NativeObject) {//if (fd.isNativeObject) {
						if (value != null)
							BridJ.readFromNative((NativeObject)value);
				}
			}
		} catch (Throwable th) {
			throw new RuntimeException("Unexpected error while reading fields from struct " + Utils.toString(desc.structType) + " (" + getPointer(struct) + ") : " + th, th);
		}
	}
	public final <T> Pointer<T> getPointerField(StructObject struct, int fieldIndex) {
        StructFieldDescription fd = desc.fields[fieldIndex];
        Pointer<T> p;
        if (fd.isArray) {
        		p = struct.peer.offset(fd.byteOffset).as(fd.nativeTypeOrPointerTargetType);
        		p = p.validElements(fd.arrayLength);
        } else {
        		p = struct.peer.getPointerAtOffset(fd.byteOffset, fd.nativeTypeOrPointerTargetType);
        }
		return p;
	}
	
	public final <T> void setPointerField(StructObject struct, int fieldIndex, Pointer<T> value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		struct.peer.setPointerAtOffset(fd.byteOffset, value);
	}
	
	public final <T extends TypedPointer> T getTypedPointerField(StructObject struct, int fieldIndex) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		PointerIO<T> pio = PointerIO.getInstance(fd.nativeTypeOrPointerTargetType);
		return pio.castTarget(struct.peer.getSizeTAtOffset(fd.byteOffset));
	}
	public final <O extends NativeObject> O getNativeObjectField(StructObject struct, int fieldIndex) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		return (O)struct.peer.offset(fd.byteOffset).getNativeObject(fd.nativeTypeOrPointerTargetType);
	}

	public final <O extends NativeObject> void setNativeObjectField(StructObject struct, int fieldIndex, O value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		struct.peer.offset(fd.byteOffset).setNativeObject(value, fd.nativeTypeOrPointerTargetType);
	}
	
	public final <E extends Enum<E> & ValuedEnum<E>> IntValuedEnum<E> getEnumField(StructObject struct, int fieldIndex) {
        StructFieldDescription fd = desc.fields[fieldIndex];
		return FlagSet.fromValue(struct.peer.getIntAtOffset(fd.byteOffset), (Class<E>)fd.nativeTypeOrPointerTargetType);
	}
	
	public final void setEnumField(StructObject struct, int fieldIndex, ValuedEnum<?> value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		struct.peer.setIntAtOffset(fd.byteOffset, (int)value.value());
	}
	
    private void setSignedIntegral(Pointer<?> ptr, long byteOffset, long byteLength, long bitMask, long bitOffset, long value) {
        if (bitMask != -1) {
            long previous = ptr.getSignedIntegralAtOffset(byteOffset, byteLength);
            value = value << bitOffset;
            value = previous & ~bitMask | value & bitMask;
        }
        ptr.setSignedIntegralAtOffset(byteOffset, value, byteLength);
    }

#foreach ($prim in $primitives)
    public final void set${prim.CapName}Field(StructObject struct, int fieldIndex, ${prim.Name} value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		#if ($prim.isSignedIntegral())
        if ($prim.Size != fd.byteLength || fd.bitMask != -1)
            setSignedIntegral(struct.peer, fd.byteOffset, fd.byteLength, fd.bitMask, fd.bitOffset, value);
        else
        #end
            struct.peer.set${prim.CapName}AtOffset(fd.byteOffset, value);
	}
	public final ${prim.Name} get${prim.CapName}Field(StructObject struct, int fieldIndex) {
		StructFieldDescription fd = desc.fields[fieldIndex];
        ${prim.Name} value;
        #if ($prim.isSignedIntegral())
        if ($prim.Size != fd.byteLength)
			value = (${prim.Name})struct.peer.getSignedIntegralAtOffset(fd.byteOffset, fd.byteLength);
        else
		#end
            value = struct.peer.get${prim.CapName}AtOffset(fd.byteOffset);
        
        #if ($prim.isSignedIntegral())
        return (${prim.Name}) ((value & fd.bitMask) >> fd.bitOffset);
        #else
        //assert fd.bitMask == -1;
        return value;
        #end
    }
#end	

#foreach ($sizePrim in ["SizeT", "CLong"])
    public final void set${sizePrim}Field(StructObject struct, int fieldIndex, long value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		struct.peer.set${sizePrim}AtOffset(fd.byteOffset, value);
	}
	public final long get${sizePrim}Field(StructObject struct, int fieldIndex) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		return struct.peer.get${sizePrim}AtOffset(fd.byteOffset);
	}
#end

  public final void setTimeTField(StructObject struct, int fieldIndex, TimeT value) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		struct.peer.setIntegralAtOffset(fd.byteOffset, value);
	}
	public final TimeT getTimeTField(StructObject struct, int fieldIndex) {
		StructFieldDescription fd = desc.fields[fieldIndex];
		return new TimeT(struct.peer.getIntegralAtOffset(fd.byteOffset, TimeT.SIZE));
	}
}
