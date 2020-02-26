/*
   Copyright 2018 - 2020 Volker Berlin (i-net software)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/
package de.inetsoftware.jwebassembly.module;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;

import de.inetsoftware.classparser.ClassFile;
import de.inetsoftware.classparser.ClassFile.Type;
import de.inetsoftware.classparser.ConstantClass;
import de.inetsoftware.classparser.FieldInfo;
import de.inetsoftware.classparser.MethodInfo;
import de.inetsoftware.jwebassembly.JWebAssembly;
import de.inetsoftware.jwebassembly.WasmException;
import de.inetsoftware.jwebassembly.wasm.AnyType;
import de.inetsoftware.jwebassembly.wasm.ArrayType;
import de.inetsoftware.jwebassembly.wasm.LittleEndianOutputStream;
import de.inetsoftware.jwebassembly.wasm.NamedStorageType;
import de.inetsoftware.jwebassembly.wasm.ValueType;

/**
 * Manage the written and to write types (classes)
 * 
 * @author Volker Berlin
 */
public class TypeManager {

    /** name of virtual function table, start with a point for an invalid Java identifier  */
    static final String             VTABLE = ".vtable";

    private static final int        VTABLE_FIRST_FUNCTION_INDEX = 2;

    private Map<String, StructType> structTypes = new LinkedHashMap<>();

    private Map<AnyType, ArrayType> arrayTypes  = new LinkedHashMap<>();

    private boolean                 isFinish;

    private final WasmOptions       options;

    /**
     * Initialize the type manager.
     * 
     * @param options
     *            compiler properties
     */
    TypeManager( WasmOptions options ) {
        this.options = options;
    }

    /**
     * Finish the prepare and write the types. Now no new types and functions should be added.
     * 
     * @param writer
     *            the targets for the types
     * @param functions
     *            the used functions for the vtables of the types
     * @param classFileLoader
     *            for loading the class files
     * @throws IOException
     *             if any I/O error occur on loading or writing
     */
    void prepareFinish( ModuleWriter writer, FunctionManager functions, ClassFileLoader classFileLoader ) throws IOException {
        isFinish = true;
        for( StructType type : structTypes.values() ) {
            type.writeStructType( writer, functions, this, classFileLoader );
        }
    }

    /**
     * Get the StructType. If needed an instance is created.
     * 
     * @param name
     *            the type name like java/lang/Object
     * @return the struct type
     */
    public StructType valueOf( String name ) {
        StructType type = structTypes.get( name );
        if( type == null ) {
            JWebAssembly.LOGGER.fine( "\t\ttype: " + name );
            if( isFinish ) {
                throw new WasmException( "Register needed type after scanning: " + name, -1 );
            }
            type = new StructType( name, structTypes.size() );
            structTypes.put( name, type );
        }
        return type;
    }

    /**
     * Get the array type for the given component type.
     * 
     * @param arrayType
     *            the component type of the array
     * @return the array type
     */
    public ArrayType arrayType( AnyType arrayType ) {
        ArrayType type = arrayTypes.get( arrayType );
        if( type == null ) {
            JWebAssembly.LOGGER.fine( "\t\ttype: " + arrayType );
            if( isFinish ) {
                throw new WasmException( "Register needed array type after scanning: " + arrayType, -1 );
            }
            type = new ArrayType( arrayType );
            arrayTypes.put( arrayType, type );
        }
        return type;
    }

    /**
     * Get the FunctionName for a virtual call and mark it as used. The function has 2 parameters (THIS,
     * virtualfunctionIndex) and returns the index of the function.
     * 
     * @return the name
     */
    @Nonnull
    WatCodeSyntheticFunctionName getCallVirtualGC() {
        return new WatCodeSyntheticFunctionName( //
                        "callVirtual", "local.get 0 " // THIS
                                        + "struct.get java/lang/Object .vtable " // vtable is on index 0
                                        + "local.get 1 " // virtualFunctionIndex
                                        + "i32.add " //
                                        + "i32.load offset=0 align=4 " //
                                        + "return " //
                        , valueOf( "java/lang/Object" ), ValueType.i32, null, ValueType.i32 ); //
    }

    /**
     * A reference to a type.
     * 
     * @author Volker Berlin
     */
    public static class StructType implements AnyType {

        private final String           name;

        private final int              classIndex;

        private int                    code = Integer.MAX_VALUE;

        private HashSet<String>        neededFields = new HashSet<>();

        private List<NamedStorageType> fields;

        private List<FunctionName>     methods;

        private Set<StructType>        instanceOFs;

        /**
         * The offset to the vtable in the data section.
         */
        private int                    vtableOffset;

        /**
         * Create a reference to type
         * 
         * @param name
         *            the Java class name
         * @param classIndex
         *            the running index of the class/type
         */
        StructType( String name, int classIndex ) {
            this.name = name;
            this.classIndex = classIndex;
        }

        /**
         * Mark that the field was used in any getter or setter.
         * 
         * @param fieldName
         *            the name of the field
         */
        void useFieldName( NamedStorageType fieldName ) {
            neededFields.add( fieldName.getName() );
        }

        /**
         * Write this struct type and initialize internal structures
         * 
         * @param writer
         *            the targets for the types
         * @param functions
         *            the used functions for the vtables of the types
         * @param types
         *            for types of fields
         * @param classFileLoader
         *            for loading the class files
         * @throws IOException
         *             if any I/O error occur on loading or writing
         */
        private void writeStructType( ModuleWriter writer, FunctionManager functions, TypeManager types, ClassFileLoader classFileLoader ) throws IOException {
            JWebAssembly.LOGGER.fine( "write type: " + name );
            fields = new ArrayList<>();
            methods = new ArrayList<>();
            instanceOFs = new LinkedHashSet<>(); // remembers the order from bottom to top class.
            instanceOFs.add( this );
            HashSet<String> allNeededFields = new HashSet<>();
            listStructFields( name, functions, types, classFileLoader, allNeededFields );
            code = writer.writeStructType( this );
        }

        /**
         * List the non static fields of the class and its super classes.
         * 
         * @param className
         *            the className to list. because the recursion this must not the name of this class
         * @param functions
         *            the used functions for the vtables of the types
         * @param types
         *            for types of fields
         * @param classFileLoader
         *            for loading the class files
         * @param allNeededFields
         *            for recursive call list this all used fields
         * @throws IOException
         *             if any I/O error occur on loading or writing
         */
        private void listStructFields( String className, FunctionManager functions, TypeManager types, ClassFileLoader classFileLoader, HashSet<String> allNeededFields ) throws IOException {
            ClassFile classFile = classFileLoader.get( className );
            if( classFile == null ) {
                throw new WasmException( "Missing class: " + className, -1 );
            }

            // interface does not need to resolve
            if( classFile.getType() == Type.Interface ) {
                return;
            }

            {
                // list all used fields
                StructType type = types.structTypes.get( className );
                if( type != null ) {
                    allNeededFields.addAll( type.neededFields );
                    instanceOFs.add( type );
                }
            }

            // add all interfaces to the instanceof
            for(ConstantClass interClass : classFile.getInterfaces() ) {
                StructType type = types.structTypes.get( className );
                if( type != null ) {
                    instanceOFs.add( type );
                }
            }

            ConstantClass superClass = classFile.getSuperClass();
            if( superClass != null ) {
                String superClassName = superClass.getName();
                listStructFields( superClassName, functions, types, classFileLoader, allNeededFields );
            } else {
                fields.add( new NamedStorageType( ValueType.i32, className, VTABLE ) );
            }

            for( FieldInfo field : classFile.getFields() ) {
                if( field.isStatic() ) {
                    continue;
                }
                if( !allNeededFields.contains( field.getName() ) ) {
                    continue;
                }
                fields.add( new NamedStorageType( className, field, types ) );
            }

            for( MethodInfo method : classFile.getMethods() ) {
                if( method.isStatic() || "<init>".equals( method.getName() ) ) {
                    continue;
                }
                FunctionName funcName = new FunctionName( method );

                int idx = 0;
                // search if the method is already in our list
                for( ; idx < methods.size(); idx++ ) {
                    FunctionName func = methods.get( idx );
                    if( func.methodName.equals( funcName.methodName ) && func.signature.equals( funcName.signature ) ) {
                        methods.set( idx, funcName ); // use the override method
                        functions.markAsNeeded( funcName ); // mark all overridden methods also as needed if the super method is used
                        break;
                    }
                }
                if( idx == methods.size() && functions.needToWrite( funcName ) ) {
                    // if a new needed method then add it
                    methods.add( funcName );
                }
                functions.setFunctionIndex( funcName, idx + VTABLE_FIRST_FUNCTION_INDEX );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getCode() {
            return code;
        }

        /**
         * Get the name of the Java type
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * The running index of the class/type for class meta data, instanceof and interface calls.
         * 
         * @return the unique index
         */
        public int getClassIndex() {
            return classIndex;
        }

        /**
         * Get the fields of this struct
         * @return the fields
         */
        public List<NamedStorageType> getFields() {
            return fields;
        }

        /**
         * Get the virtual function/methods
         * 
         * @return the methods
         */
        public List<FunctionName> getMethods() {
            return methods;
        }

        /**
         * Write the struct/class meta data to the datastream and set the offset position.
         * 
         * @param dataStream
         *            the target stream
         * @param getFunctionsID
         *            source for function IDs
         * @throws IOException
         *            should never occur
         */
        public void writeToStream( ByteArrayOutputStream dataStream, ToIntFunction<FunctionName> getFunctionsID ) throws IOException {
            this.vtableOffset = dataStream.size();

            LittleEndianOutputStream header = new LittleEndianOutputStream( dataStream );
            LittleEndianOutputStream data = new LittleEndianOutputStream();
            for( FunctionName funcName : methods ) {
                int functIdx = getFunctionsID.applyAsInt( funcName );
                data.writeInt32( functIdx );
            }

            header.writeInt32( data.size() + VTABLE_FIRST_FUNCTION_INDEX * 4 ); // offset of interface calls
            //TODO interface calls

            header.writeInt32( data.size() + VTABLE_FIRST_FUNCTION_INDEX * 4 ); // offset of instanceeof list
            data.writeInt32( instanceOFs.size() );
            for( StructType type : instanceOFs ) {
                data.writeInt32( type.getClassIndex() );
            }

            data.writeTo( dataStream );
        }

        /**
         * Get the vtable offset.
         * 
         * @return the offset
         */
        public int getVTable() {
            return this.vtableOffset;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "$" + name;
        }
    }
}
