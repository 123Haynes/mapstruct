/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.internal.model.source;

import java.util.Arrays;

import org.mapstruct.ap.internal.model.common.BuilderType;
import org.mapstruct.ap.internal.model.common.Type;
import org.mapstruct.ap.internal.util.Strings;
import org.mapstruct.ap.internal.util.accessor.Accessor;

/**
 * A PropertyEntry contains information on the name, readAccessor (for source), readAccessor and writeAccessor
 * (for targets) and return type of a property.
 */
public class PropertyEntry {

    private final String[] fullName;
    private final Accessor readAccessor;
    private final Accessor writeAccessor;
    private final Accessor presenceChecker;
    private final Type type;
    private final BuilderType builderType;

    /**
     * Constructor used to create {@link TargetReference} property entries from a mapping
     *
     * @param fullName
     * @param readAccessor
     * @param writeAccessor
     * @param type
     */
    private PropertyEntry(String[] fullName, Accessor readAccessor, Accessor writeAccessor,
                          Accessor presenceChecker, Type type, BuilderType builderType) {
        this.fullName = fullName;
        this.readAccessor = readAccessor;
        this.writeAccessor = writeAccessor;
        this.presenceChecker = presenceChecker;
        this.type = type;
        this.builderType = builderType;
    }

    /**
     * Constructor used to create {@link TargetReference} property entries
     *
     * @param fullName name of the property (dot separated)
     * @param readAccessor its read accessor
     * @param writeAccessor its write accessor
     * @param type type of the property
     * @param builderType the builder for the type
     * @return the property entry for given parameters.
     */
    public static PropertyEntry forTargetReference(String[] fullName, Accessor readAccessor,
                                                   Accessor writeAccessor, Type type, BuilderType builderType ) {
        return new PropertyEntry( fullName, readAccessor, writeAccessor, null, type, builderType );
    }

    /**
     * Constructor used to create {@link SourceReference} property entries from a mapping
     *
     * @param name name of the property (dot separated)
     * @param readAccessor its read accessor
     * @param presenceChecker its presence Checker
     * @param type type of the property
     * @return the property entry for given parameters.
     */
    public static PropertyEntry forSourceReference(String name, Accessor readAccessor,
                                                   Accessor presenceChecker, Type type) {
        return new PropertyEntry( new String[]{name}, readAccessor, null, presenceChecker, type, null );
    }

    public String getName() {
        return fullName[fullName.length - 1];
    }

    public Accessor getReadAccessor() {
        return readAccessor;
    }

    public Accessor getWriteAccessor() {
        return writeAccessor;
    }

    public Accessor getPresenceChecker() {
        return presenceChecker;
    }

    public Type getType() {
        return type;
    }

    public BuilderType getBuilderType() {
        return builderType;
    }

    public String getFullName() {
        return Strings.join( Arrays.asList(  fullName ), "." );
    }

    public PropertyEntry pop() {
        if ( fullName.length > 1 ) {
            String[] newFullName = Arrays.copyOfRange( fullName, 1, fullName.length );
            return new PropertyEntry(newFullName, readAccessor, writeAccessor, presenceChecker, type, builderType );
        }
        else {
            return null;
        }
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 23 * hash + Arrays.deepHashCode( this.fullName );
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj ) {
            return true;
        }
        if ( obj == null ) {
            return false;
        }
        if ( getClass() != obj.getClass() ) {
            return false;
        }
        final PropertyEntry other = (PropertyEntry) obj;
        return Arrays.deepEquals( this.fullName, other.fullName );
    }

    @Override
    public String toString() {
        return type + " " + Strings.join( Arrays.asList( fullName ), "." );
    }
}
