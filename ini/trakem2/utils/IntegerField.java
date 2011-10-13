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