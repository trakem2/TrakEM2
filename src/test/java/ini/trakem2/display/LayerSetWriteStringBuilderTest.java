/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2025 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ini.trakem2.display;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises {@link LayerSet#writeStringBuilder(java.io.Writer, StringBuilder)}
 * across the different runtime layouts seen in StringBuilder.
 */
public class LayerSetWriteStringBuilderTest {

	private Field sbvalueField;
	private Field sbcoderField;
	private Method writeMethod;

	private Object originalSbvalue;
	private Object originalSbcoder;

	@Before
	public void setUp() throws Exception {
		sbvalueField = LayerSet.class.getDeclaredField("sbvalue");
		sbvalueField.setAccessible(true);
		sbcoderField = LayerSet.class.getDeclaredField("sbcoder");
		sbcoderField.setAccessible(true);
		writeMethod = LayerSet.class.getDeclaredMethod("writeStringBuilder", java.io.Writer.class, StringBuilder.class);
		writeMethod.setAccessible(true);

		originalSbvalue = sbvalueField.get(null);
		originalSbcoder = sbcoderField.get(null);
	}

	@After
	public void tearDown() throws Exception {
		sbvalueField.set(null, originalSbvalue);
		sbcoderField.set(null, originalSbcoder);
	}

	@Test
	public void fallsBackWhenReflectionUnavailable() throws Exception {
		sbvalueField.set(null, null);
		sbcoderField.set(null, null);

		RecordingWriter writer = new RecordingWriter();
		StringBuilder builder = new StringBuilder("fallback");

		invokeWrite(writer, builder);

		assertTrue("Expected String-based write when reflection is disabled", writer.usedString);
		assertFalse("Char-array path should not be used when reflection is disabled", writer.usedCharArray);
		assertEquals(builder.toString(), writer.content());
	}

	@Test
	public void writesLatin1BackedBuilder() throws Exception {
		Object sbvalue = sbvalueField.get(null);
		Object sbcoder = sbcoderField.get(null);
		assumeNotNull(sbvalue, sbcoder);

		Field coderField = (Field) sbcoder;
		coderField.setAccessible(true);

		StringBuilder builder = new StringBuilder("ascii-only");
		byte coder = coderField.getByte(builder);
		assertEquals("Expected LATIN1 coder", 0, coder);

		RecordingWriter writer = new RecordingWriter();
		invokeWrite(writer, builder);

		assertTrue("Char-array path should be used for byte-backed builders", writer.usedCharArray);
		assertFalse("String-based fallback should not be triggered", writer.usedString);
		assertEquals(builder.toString(), writer.content());
	}

	@Test
	public void writesUtf16BackedBuilder() throws Exception {
		Object sbvalue = sbvalueField.get(null);
		Object sbcoder = sbcoderField.get(null);
		assumeNotNull(sbvalue, sbcoder);

		Field coderField = (Field) sbcoder;
		coderField.setAccessible(true);

		StringBuilder builder = new StringBuilder();
		builder.append('\u0101').append('\u05D0');
		byte coder = coderField.getByte(builder);
		assertEquals("Expected UTF16 coder", 1, coder);

		RecordingWriter writer = new RecordingWriter();
		invokeWrite(writer, builder);

		assertTrue("Char-array path should be used for UTF16 byte-backed builders", writer.usedCharArray);
		assertFalse("String-based fallback should not be triggered", writer.usedString);
		assertEquals(builder.toString(), writer.content());
	}

	private void invokeWrite(RecordingWriter writer, StringBuilder builder) throws Exception {
		try {
			writeMethod.invoke(null, writer, builder);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new RuntimeException(cause);
		}
	}

	private static final class RecordingWriter extends Writer {
		private final StringBuilder out = new StringBuilder();
		private boolean usedCharArray;
		private boolean usedString;

		@Override
		public void write(char[] cbuf, int off, int len) {
			usedCharArray = true;
			out.append(cbuf, off, len);
		}

		@Override
		public void write(int c) {
			usedCharArray = true;
			out.append((char) c);
		}

		@Override
		public void write(String str, int off, int len) {
			usedString = true;
			out.append(str, off, off + len);
		}

		@Override
		public void flush() throws IOException {
			// no-op
		}

		@Override
		public void close() throws IOException {
			// no-op
		}

		String content() {
			return out.toString();
		}
	}
}
