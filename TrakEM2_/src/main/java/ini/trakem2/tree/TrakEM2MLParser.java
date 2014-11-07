/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.tree;


import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;


/**
 * A class to parse a file in TrakEM2 XML format.
 *
 */
public class TrakEM2MLParser {

	/** The root element of the template. */
	TemplateThing root = null;

	public TrakEM2MLParser(String file_path) {
		InputStream i_stream = null;
		try {
			/* // I don't have this apache parser!
			XMLReader parser = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			org.xml.sax.ContentHandler handler = new TrakEM2MLHandler();
			parser.setContentHandler(handler);
			i_stream = new BufferedInputStream(new FileInputStream(file_path));
			InputSource input_source = new InputSource(i_stream);
			parser.parse(input_source);
			*/

			//trying internal java parser
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setValidating(true);
			SAXParser parser = factory.newSAXParser();
				//SAXParserFactory.newSAXParser();
			i_stream = new BufferedInputStream(new FileInputStream(file_path));
			InputSource input_source = new InputSource(i_stream);
			org.xml.sax.helpers.DefaultHandler handler = new TrakEM2MLHandler();
			parser.parse(input_source, handler);

		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != i_stream) {
				try {
					i_stream.close();
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}
	}

	/** Note: will close the xml_stream. */
	public TrakEM2MLParser(InputStream xml_stream) {
		try {
			//internal java parser
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setValidating(true);
			SAXParser parser = factory.newSAXParser();
				//SAXParserFactory.newSAXParser();
			InputSource input_source = new InputSource(xml_stream);
			org.xml.sax.helpers.DefaultHandler handler = new TrakEM2MLHandler();
			parser.parse(input_source, handler);

		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != xml_stream) {
				try {
					xml_stream.close();
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}
	}

	/** Get the root node of the parsed XML template tree. */
	public TemplateThing getTemplateRoot() {
		return root;
	}

	/** This handle class constructs the hierarchical tree of TemplateThing objects. */
	class TrakEM2MLHandler extends DefaultHandler {

		private TemplateThing current_thing = null;
		private ArrayList<TemplateThing> al_open = new ArrayList<TemplateThing>();

		public void startElement(String namespace_URI, String local_name, String qualified_name, Attributes attributes) throws SAXException {

			//debug:
			//Utils.log("local_name: " + local_name);
			//Utils.log("qualified_name: " + qualified_name);

			//create a new Thing
			TemplateThing new_thing = new TemplateThing(qualified_name);
			//set the new_thing as the current (to call setValue(String) on it later in the void character(...) method):
			current_thing = new_thing;
			// set the new_thing as the root if there is no root yet
			if (null == root) {
				root = new_thing;
			}
			// get the previously open thing and add this new_thing to it as a child
			int size = al_open.size();
			if (size > 0) {
				TemplateThing parent = (TemplateThing)al_open.get(size -1);
				parent.addChild(new_thing);
			}
			//set the new Thing as open
			al_open.add(new_thing);
		}

		public void endElement(String namespace_URI, String local_name, String qualified_name) {
			// iterate over all open things and find the one that matches the local_name, and set it closed (pop it out of the list):
			for (int i=al_open.size() -1; i>-1; i--) {
				TemplateThing tt = (TemplateThing)al_open.get(i);
				if (tt.getType().equals(qualified_name)) {
					al_open.remove(tt);
					break;
				}
			}
		}

		public void characters(char[] c, int start, int length) {
			//Utils.log("Characters: " + new String(c));
			if (length > 0) {
				String value = new String(c, start, length).trim();
				if (value.length() > 0) {
					current_thing.setValue(value);
				}
			}

			//now check if in the same line of the start element there is a closing element, since in such a case the endElement method below would never be closed:
			String type = current_thing.getType();
			String line = new String(c);
			if (-1 != line.indexOf("/" + type)) {
				//debug:
				//Utils.log("calling endElement from characters");
				endElement(null, null, type);
			}
		}

		public void fatalError(SAXParseException e) /*throws SAXParseException */{
			Utils.log("Fatal error: column=" + e.getColumnNumber() + " line=" + e.getLineNumber());
			// signal:
			root = null;
		}

		public void skippedEntity(String name) {
			Utils.log("SAX Parser has skipped: " + name);
		}

		public void notationDeclaration(String name, String publicId, String systemId) {
			Utils.log("Notation declaration: " + name + ", " + publicId + ", " + systemId);
		}

		public void warning(SAXParseException e) {
			Utils.log("SAXParseException : " + e);
		}
	}
}
