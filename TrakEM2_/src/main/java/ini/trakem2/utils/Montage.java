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

package ini.trakem2.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class Montage {

	private HashMap<String,HashMap<String,Item>> ht;
	private ArrayList<Item> al;
	private int i_row_start = -1;
	private int i_row_end = -1;
	private int i_col_start = -1;
	private int i_col_end = -1;

	public Montage(String convention, boolean chars_are_columns) {
		convention = convention.toLowerCase();
		al = new ArrayList<Item>();
		ht = new HashMap<String,HashMap<String,Item>>();
	
		// find out the start and end index of the char string that defines the chunks for the rows and cols in the name
		int start, end;
		start = convention.indexOf('c');
		end = start + 1;
		if (convention.length() != end) {
			while (end < convention.length() && 'c' == convention.charAt(end)) {
				end++;
			}
		}
		if (chars_are_columns) {
			i_col_start = start;
			i_col_end = end;
		} else {
			i_row_start = start;
			i_row_end = end;
		}
		start = convention.indexOf('d');
		end = start + 1;
		if (convention.length() != end) {
			while (end < convention.length() && 'd' == convention.charAt(end)) {
				end++;
			}
		}
		if (chars_are_columns) {
			i_row_start = start;
			i_row_end = end;
		} else {
			i_col_start = start;
			i_col_end = end;
		}

		//Utils.log2("Montage: i_row_start,end : " + i_row_start + "," + i_row_end + "   i_col_start,end : " + i_col_start + "," + i_col_end);
	}

	public void addAll(String[] file_name) {
		for (int i=0; i<file_name.length; i++) {
			add(file_name[i]);
		}
	}

	public void add(String file_name) {
		Item item = new Item(file_name);
		al.add(item);
		HashMap<String,Item> ob = ht.get(item.s_col);
		if (null == ob) {
			HashMap<String,Item> rows = new HashMap<String,Item>();
			rows.put(item.s_row, item);
			ht.put(item.s_col, rows);
		} else {
			ob.put(item.s_row, item);
		}
	}

	/** Returns an ArrayList of String[], which can be of unequal length, and each contain the file names of a column. First dimension is columns, second is rows. */
	public ArrayList<String[]> getCols() {

		// TODO problem: if an image is missing in the middle of the grid, the column will be shifted up
		// It'd be better to identify col and row markers, and then fill in a grid pattern with them. If there are holes, so what ... but then, if images are of unequal size, that won't work ..
		// Whatever, it's fine as it is (and easy enough to fix manually when such a problem occurs)


		// get cols, and order them
		String[] cols = new String[ht.size()];
		int i = 0;
		int max_col_len = 0;
		for (final String c : ht.keySet()) {
			cols[i] = c;
			int len = cols[i].length();
			if (len > max_col_len) max_col_len = len;
			i++;
		}
		//fix length of col string: prepend '0' to any that is shorter than the longest for Arrays.sort() to work ok
		for (i=al.size()-1;i>-1; i--) {
			Item item = (Item)al.get(i);
			item.fixColLength(max_col_len);
		}
		Arrays.sort(cols); // you do it, java ..

		ArrayList<String[]> mon = new ArrayList<String[]>();
		for (i=0; i<cols.length; i++) {
			HashMap<String,Item> ht_rows = ht.get(cols[i]);
			String[] rows = new String[ht_rows.size()];
			int j = 0;
			int max_row_len = 0;
			for (final String r : ht_rows.keySet()) {
				rows[j] = r;
				int len = rows[j].length();
				if (len > max_row_len) max_row_len = len;
				j++;
			}
			for (final Item item : ht_rows.values()) {
				item.fixRowLength(max_row_len);
			}
			Arrays.sort(rows); //ordering by whatever ordering there is, letters or numbers
			String[] ob_rows = new String[rows.length];
			for (j=0; j<rows.length; j++) {
				ob_rows[j] = ((Item)ht_rows.get(rows[j])).file_name;
			}
			// add it
			mon.add(ob_rows);
		}

		return mon;
	}

	private class Item {
		String file_name;
		String s_col;
		String s_row;
		Item(String file_name) {
			this.file_name = file_name;
			this.s_col = file_name.substring(i_col_start, i_col_end);
			this.s_row = file_name.substring(i_row_start, i_row_end);
		}

		void fixColLength(int target_len) {
			int len = s_col.length();
			while (target_len > len) {
				s_col = "0" + s_col;
				len++;
			}
		}

		void fixRowLength(int target_len) {
			int len = s_row.length();
			while (target_len > len) {
				s_row = "0" + s_row;
				len++;
			}
		}
	}

}
