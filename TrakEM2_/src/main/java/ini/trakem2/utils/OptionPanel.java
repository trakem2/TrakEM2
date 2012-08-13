package ini.trakem2.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;

public class OptionPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private GridBagLayout bl = new GridBagLayout();
	private GridBagConstraints c = new GridBagConstraints();

	private ActionListener tl = new ActionListener() {
		public void actionPerformed(ActionEvent ae) {
			Component source = (Component) ae.getSource();
			try {
				Setter s = setters.get(source);
				if (null != s) s.setFrom(source);
			} catch (Exception e) {
				Utils.log2("Invalid value!");
			}
		}
	};

	private KeyListener kl = new KeyAdapter() {
		public void keyReleased(KeyEvent ke) {
			Component source = (Component) ke.getSource();
			try {
				Setter s = setters.get(source);
				if (null != s) s.setFrom(source);
			} catch (Throwable t) {
				Utils.logAll("Invalid value " + ((JTextField)source).getText());
			}
		}
	};
	
	private MouseWheelListener mwl = new MouseWheelListener() {
		@Override
		public void mouseWheelMoved(MouseWheelEvent mwe) {
			Component source = (Component) mwe.getSource();
			try {
				Setter s = setters.get(source);
				if (s instanceof NumericalSetter) {
					if (null != s) {
						// Update the value of the field and also of the JTextField
						((JTextField)source).setText(((NumericalSetter)s).setFrom(source, mwe.getWheelRotation()).toString());
					}
				}
			} catch (Throwable t) {
				Utils.logAll("Invalid value " + ((JTextField)source).getText());
			}
		}
	};

	public OptionPanel() {
		super();
		setLayout(bl);
		c.ipady = 10;
	}

	private List<JTextField> numeric_fields = new ArrayList<JTextField>();
	private List<JCheckBox> checkboxes = new ArrayList<JCheckBox>();
	private List<JComboBox> choices = new ArrayList<JComboBox>();
	private List<Component> all = new ArrayList<Component>();
	private HashMap<Component,Setter> setters = new HashMap<Component,Setter>();

	private int rows = 0;

	public void addMessage(String text) {
		JLabel label = new JLabel(text);
		c.gridy = rows++;
		c.gridx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.weightx = 1;
		c.weighty = 0;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.NONE;
		bl.setConstraints(label, c);
		add(label);
	}

	public void bottomPadding() {
		c.gridy = rows++;
		c.gridx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		JPanel space = new JPanel();
		bl.setConstraints(space, c);
		add(space);
	}

	/** The left-side label of a field. */
	private void addLabel(String label) {
		c.gridy = rows++;
		c.gridx = 0;
		JLabel l = new JLabel(label);
		c.anchor = GridBagConstraints.NORTHEAST;
		c.weightx = 1;
		c.weighty = 0;
		c.ipadx = 10;
		c.gridwidth = 1;
		c.fill = GridBagConstraints.NONE;
		bl.setConstraints(l, c);
		add(l);
	}

	/** The right-side field of a label. */
	private void addField(Component comp, Setter setter) {
		c.gridx = 1;
		c.weightx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.ipadx = 0;
		c.gridwidth = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		bl.setConstraints(comp, c);
		add(comp);
		setters.put(comp, setter);
	}

	public JTextField addNumericField(String label, double value, int digits) {
		return addNumericField(label, value, digits, null);
	}

	public JTextField addNumericField(String label, double value, int digits, Setter setter) {
		return addNumericField(label, Utils.cutNumber(value, digits), setter);
	}

	public JTextField addNumericField(String label, int value) {
		return addNumericField(label, value, null);
	}

	public JTextField addNumericField(String label, int value, Setter setter) {
		return addNumericField(label, Integer.toString(value), setter);
	}

	private JTextField addNumericField(String label, String value, Setter setter) {
		addLabel(label);
		JTextField tval = new JTextField(value, 7);
		numeric_fields.add(tval);
		addField(tval, setter);
		tval.addKeyListener(kl);
		tval.addMouseWheelListener(mwl);
		return tval;
	}

	public JCheckBox addCheckbox(String label, boolean selected) {
		return addCheckbox(label, selected, null);
	}

	public JCheckBox addCheckbox(String label, boolean selected, Setter setter) {
		addLabel(label);
		JCheckBox cb = new JCheckBox();
		cb.setSelected(selected);
		checkboxes.add(cb);
		all.add(cb);
		addField(cb, setter);
		cb.addActionListener(tl);
		return cb;
	}

	public JComboBox addChoice(String label, String[] items, int selected) {
		return addChoice(label, items, selected, null);
	}

	public JComboBox addChoice(String label, String[] items, int selected, Setter setter) {
		addLabel(label);
		JComboBox choice = new JComboBox(items);
		choice.setBackground(Color.white);
		choice.setSelectedIndex(selected);
		choice.addActionListener(tl);
		all.add(choice);
		addField(choice, setter);
		return choice;
	}



	public List<JTextField> getNumericFields() {
		return new ArrayList<JTextField>(numeric_fields);
	}

	/** May throw IllegalArgumentException or NumberFormatException */
	public double getNumber(int index) throws Exception {
		check(numeric_fields, index);
		return Double.parseDouble(numeric_fields.get(index).getText());
	}

	public List<JCheckBox> getCheckBoxes() {
		return new ArrayList<JCheckBox>(checkboxes);
	}

	/** May throw IllegalArgumentException */
	public boolean getCheckbox(int index) throws Exception {
		check(checkboxes, index);
		return checkboxes.get(index).isSelected();
	}

	public List<JComboBox> getChoices() {
		return new ArrayList<JComboBox>(choices);
	}

	public int getChoiceIndex(int index) throws Exception {
		check(choices, index);
		return choices.get(index).getSelectedIndex();
	}

	public String getChoiceString(int index) throws Exception {
		return getChoiceObject(index).toString();
	}

	public Object getChoiceObject(int index) throws Exception {
		check(choices, index);
		return choices.get(index).getSelectedItem();
	}


	private void check(List<?> list, int index) throws Exception {
		if (index < 0 || index > list.size() -1) throw new IllegalArgumentException("Index out of bounds: " + index);
	}

	static abstract public class Setter {
		protected final Object ob;
		protected final String field;
		protected Runnable reaction;
		public Setter(Object ob, String field) {
			this.ob = ob;
			this.field = field;
		}
		public Setter(Object ob, String field, Runnable reaction) {
			this(ob, field);
			this.reaction = reaction;
		}
		/** Will set the field if no exception is thrown when reading it. */
		public void setFrom(Component source) throws Exception {
			Field f = ob.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(ob, getValue(source));

			//Utils.log2("set value of " + field + " to " + f.get(ob));
			
			if (null != reaction) reaction.run();
		}
		abstract public Object getValue(Component source);
	}
	
	static abstract public class NumericalSetter extends Setter {
		protected int min = Integer.MIN_VALUE,
					  max = Integer.MAX_VALUE;
		public NumericalSetter(Object ob, String field) {
			super(ob, field);
		}
		public NumericalSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public NumericalSetter(Object ob, String field, Runnable reaction, int min, int max) {
			super(ob, field, reaction);
			this.min = min;
			this.max = max;
		}
		public Object setFrom(Component source, int inc) throws Exception {
			Field f = ob.getClass().getDeclaredField(field);
			f.setAccessible(true);
			Object val = getValue(source, inc);
			f.set(ob, val);

			Utils.log2("set value of " + field + " to " + f.get(ob));
			
			if (null != reaction) reaction.run();
			
			return val;
		}
		abstract protected Object getValue(Component source, int inc);
	}

	static public class IntSetter extends NumericalSetter {
		public IntSetter(Object ob, String field) {
			super(ob, field);
		}
		public IntSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public IntSetter(Object ob, String field, Runnable reaction, int min, int max) {
			super(ob, field, reaction, min, max);
		}
		public Object getValue(Component source) {
			return (int) Double.parseDouble(((JTextField)source).getText());
		}
		protected Object getValue(Component source, int inc) {
			int val = ((int) Double.parseDouble(((JTextField)source).getText())) + inc;
			if (val < this.min) return this.min;
			if (val > this.max) return this.max;
			return val;
		}
	}

	static public class DoubleSetter extends NumericalSetter {
		public DoubleSetter(Object ob, String field) {
			super(ob, field);
		}
		public DoubleSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public DoubleSetter(Object ob, String field, Runnable reaction, int min, int max) {
			super(ob, field, reaction, min, max);
		}
		public Object getValue(Component source) {
			return Double.parseDouble(((JTextField)source).getText());
		}
		protected Object getValue(Component source, int inc) {
			double val = Double.parseDouble(((JTextField)source).getText()) + inc;
			if (val < this.min) return (double)this.min;
			if (val > this.max) return (double)this.max;
			return val;
		}
	}
	
	static public class FloatSetter extends NumericalSetter {
		public FloatSetter(Object ob, String field) {
			super(ob, field);
		}
		public FloatSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public FloatSetter(Object ob, String field, Runnable reaction, int min, int max) {
			super(ob, field, reaction, min, max);
		}
		public Object getValue(Component source) {
			return Float.parseFloat(((JTextField)source).getText());
		}
		public Object getValue(Component source, int inc) {
			float val = Float.parseFloat(((JTextField)source).getText()) + inc;
			if (val < this.min) return (float)this.min;
			if (val > this.max) return (float)this.max;
			return val;
		}
	}

	static public class BooleanSetter extends Setter {
		public BooleanSetter(Object ob, String field) {
			super(ob, field);
		}
		public BooleanSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public Object getValue(Component source) {
			return ((JCheckBox)source).isSelected();
		}
	}

	static public class StringSetter extends Setter {
		public StringSetter(Object ob, String field) {
			super(ob, field);
		}
		public StringSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public Object getValue(Component source) {
			return ((JTextComponent)source).getText();
		}
	}

	static public class ChoiceIntSetter extends Setter {
		public ChoiceIntSetter(Object ob, String field) {
			super(ob, field);
		}
		public ChoiceIntSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public Object getValue(Component source) {
			return ((JComboBox)source).getSelectedIndex();
		}
	}

	static public class ChoiceStringSetter extends Setter {
		public ChoiceStringSetter(Object ob, String field) {
			super(ob, field);
		}
		public ChoiceStringSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public Object getValue(Component source) {
			return ((JComboBox)source).getSelectedItem().toString();
		}
	}

	static public class ChoiceObjectSetter extends Setter {
		public ChoiceObjectSetter(Object ob, String field) {
			super(ob, field);
		}
		public ChoiceObjectSetter(Object ob, String field, Runnable reaction) {
			super(ob, field, reaction);
		}
		public Object getValue(Component source) {
			return ((JComboBox)source).getSelectedItem();
		}
	}
}
