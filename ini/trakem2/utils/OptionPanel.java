package ini.trakem2.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
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

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
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

    private ChangeListener cl = new ChangeListener() {
        @Override
        public void stateChanged(ChangeEvent e) {
            final Object source = e.getSource();
            if (source instanceof Component) {
                final Component component = (Component) source;
                final Setter s = setters.get(component);
                if (s != null) {
                    try {
                        s.setFrom(component);
                    } catch (Throwable t) {
                        Utils.logAll("Invalid value " + s.getValue(component));
                    }
                }
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

    /**
     * Adds a numeric field controlled by a slider to this option panel.
     *
     * @param  labelText  the field's name or label.
     * @param  value      the field's initial value.
     * @param  setter     sets the field instance value from the slider
     *                    user interface component's value.
     *
     * @return the panel container for all of the slider components created
     *         for the field.
     */
    public SliderPanel addSliderField(String labelText,
                                      int value,
                                      int min,
                                      int max,
                                      SliderSetter setter) {
        SliderPanel sliderPanel = new SliderPanel(labelText,
                                                  value,
                                                  min,
                                                  max);
        JSlider slider = sliderPanel.getSlider();
        slider.addChangeListener(cl);
        setters.put(slider, setter);

        c.gridy = rows++;
        c.gridx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.weightx = 1;
        c.weighty = 0;
        c.ipadx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        bl.setConstraints(sliderPanel, c);
        add(sliderPanel);

        return sliderPanel;
    }

    /**
     * Adds a check box field to this option panel that can control the
     * visibility of other user interface components
     * (presumably also in the panel).
     *
     * The {@link VisibilityCheckBox#setVisibilityComponents(java.util.List, java.util.List)}
     * method on the returned component can be used to define the list of
     * controlled components.
     *
     * @param  labelText  the field's name or label.
     * @param  selected   the field's initial value.
     * @param  setter     sets the field instance value from the check box
     *                    user interface component's value.
     *
     * @return the check box component created for the field.
     */
    public VisibilityCheckBox addVisibilityCheckBox(String labelText,
                                                    boolean selected,
                                                    Setter setter) {
        addLabel(labelText);
        VisibilityCheckBox checkBox = new VisibilityCheckBox();
        checkBox.setSelected(selected);
        checkboxes.add(checkBox);
        all.add(checkBox);
        addField(checkBox, setter);
        checkBox.addActionListener(tl);
        return checkBox;
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

    /**
     * Field setter tied to a JSlider component.
     */
    static public class SliderSetter extends Setter {
        public SliderSetter(Object ob,
                            String field,
                            Runnable reaction) {
            super(ob, field, reaction);
        }
        public Object getValue(Component source) {
            return ((JSlider)source).getValue();
        }
    }

    /**
     * Panel containing labels for the slider name and slider value
     * plus the slider itself.  The slider value label is kept in-sync
     * with the slider.
     *
     * The panel looks something like this:
     * <pre>
     * Name: Value
     *  |---v-------------|
     * min               max
     * </pre>
     */
    static public class SliderPanel extends JPanel implements ChangeListener {

        private JLabel sliderValueLabel;
        private JSlider slider;

        public SliderPanel(String labelText,
                           int value,
                           int min,
                           int max) {

            super();
            this.setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
            this.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 0));

            final JLabel sliderLabel = new JLabel(labelText);
            this.sliderValueLabel = new JLabel(String.valueOf(value));

            this.slider = new JSlider(JSlider.HORIZONTAL, min, max, value);
            this.slider.setMajorTickSpacing(max - min);
            this.slider.setPaintLabels(true);
            this.slider.addChangeListener(this);

            JPanel labelPanel =
                    new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            labelPanel.add(sliderLabel);
            labelPanel.add(sliderValueLabel);
            this.add(labelPanel);
            this.add(this.slider);
        }

        public JSlider getSlider() {
            return slider;
        }

        @Override
        public void stateChanged(ChangeEvent e) {
            sliderValueLabel.setText(String.valueOf(slider.getValue()));
        }
    }

    /**
     * A check box that can control the visibility of other user
     * interface components.
     */
    static public class VisibilityCheckBox extends JCheckBox implements ActionListener {
        private List<? extends Component> visibleWhenOn;
        private List<? extends Component> visibleWhenOff;

        public VisibilityCheckBox() {
            super();
            this.addActionListener(this);
            this.visibleWhenOn = null;
            this.visibleWhenOff = null;
        }

        /**
         * Sets the list of components controlled by this check box.
         *
         * @param  visibleWhenOn    list of components to be made visible
         *                          when this check box is selected (and
         *                          hidden when it is NOT selected);
         *                          can be null or empty.
         * @param  visibleWhenOff   list of components to be made visible
         *                          when this check box is NOT selected (and
         *                          hidden when it is selected);
         *                          can be null or empty.
         */
        public void setVisibilityComponents(List<? extends Component> visibleWhenOn,
                                            List<? extends Component> visibleWhenOff) {
            this.visibleWhenOn = visibleWhenOn;
            this.visibleWhenOff = visibleWhenOff;
            updateVisibility();
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            updateVisibility();
        }

        private void updateVisibility() {
            final boolean isOn = isSelected();
            final boolean isOff = ! isOn;
            if (visibleWhenOn != null) {
                for (Component c : visibleWhenOn) {
                    c.setVisible(isOn);
                }
            }
            if (visibleWhenOff != null) {
                for (Component c : visibleWhenOff) {
                    c.setVisible(isOff);
                }
            }
        }
    }
}
