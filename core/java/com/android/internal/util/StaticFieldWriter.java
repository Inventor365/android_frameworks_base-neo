/*
 * SPDX-FileCopyrightText: Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * Overwrites {@code static final} fields. Since Android 17, ART rejects reflective writes to them
 * (including android.os.Build) for apps targeting C+, and JNI SetStaticObjectField aborts, so the
 * value is written straight into the declaring class at the field's offset.
 *
 * @hide
 */
public final class StaticFieldWriter {

    private StaticFieldWriter() {}

    /** Sets {@code clazz.name} to {@code value}; int fields take an {@link Integer}. */
    public static void set(Class<?> clazz, String name, Object value)
            throws ReflectiveOperationException {
        Field field = clazz.getDeclaredField(name);
        Field offsetField = Field.class.getDeclaredField("offset");
        offsetField.setAccessible(true);
        long offset = offsetField.getInt(field);
        Unsafe unsafe = Unsafe.getUnsafe();
        if (field.getType() == Integer.TYPE) {
            unsafe.putInt(clazz, offset, (Integer) value);
        } else {
            unsafe.putObject(clazz, offset, value);
        }
    }
}
