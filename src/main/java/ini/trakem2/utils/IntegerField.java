/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
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
package ini.trakem2.utils;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import javax.swing.JTextField;

public final class IntegerField extends JTextField {
	private static final long serialVersionUID = 1L;
	private final int maxDigits;
	public IntegerField(int initial, int maxDigits) {
		super();
		this.maxDigits = maxDigits;
		String text = Integer.toString(initial);
		if (text.length() > maxDigits) text = text.substring(0, maxDigits);
		setText(text);
	}
	@Override
	protected void processKeyEvent(KeyEvent ke) {
		if (0 != ke.getModifiers()) return;
		if (ke.getID() != KeyEvent.KEY_PRESSED) return;
		switch (ke.getKeyCode()) {
			case KeyEvent.VK_DELETE:
			case KeyEvent.VK_BACK_SPACE:
			case KeyEvent.VK_ENTER:
			case KeyEvent.VK_LEFT:
			case KeyEvent.VK_RIGHT:
				super.processKeyEvent(ke);
				for (KeyListener kl : getKeyListeners()) {
					kl.keyPressed(ke);
				}
				return;
		}
		// For keyPressed only:
		if (Character.isDigit(ke.getKeyChar())) {
			if (getText().length() < maxDigits) {
				setText(getText() + ke.getKeyChar());
				for (KeyListener kl : getKeyListeners()) {
					kl.keyPressed(ke);
				}
			}
		}
	}
	@Override
	public String getText() {
		final String text = super.getText();
		if (0 == text.length()) return "0";
		return text;
	}
}
