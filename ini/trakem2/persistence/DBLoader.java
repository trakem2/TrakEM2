/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.persistence;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.io.TiffEncoder;
import ij.process.ImageProcessor;
import ij.process.StackProcessor;
import ij.measure.Calibration;
import ini.trakem2.Project;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.Ball;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Snapshot;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.tree.Attribute;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.ProjectAttribute;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.TemplateAttribute;
import ini.trakem2.tree.Thing;
import ini.trakem2.tree.TrakEM2MLParser;
import ini.trakem2.tree.DTDParser;
import ini.trakem2.tree.DNDTree;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.utils.*;
import ini.trakem2.io.*;

import java.awt.GridLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FileDialog;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Label;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.geom.Area;
import java.awt.Polygon;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.*;
import org.postgresql.core.PGStream;
import org.postgresql.core.v3.*;
import org.postgresql.jdbc2.*;
import org.postgresql.jdbc3.*;

public class DBLoader extends Loader {
	
	// make them null for a release.
	private String db_host = "127.0.0.1";
	private String db_port = "5432";
	private String db_name = null;
	private String db_user = null;
	private String db_pw = null;

	private Connection connection = null;
	static private boolean driver_loaded = false;
	private Monitor monitor = null;

	/** Check if settings are in. */
	public boolean isReady() {
		return null != this.db_host && null != this.db_port && null != this.db_name && null != this.db_user && null != this.db_pw;
	}

	/** Create and connect to the database. */
	public DBLoader() {
		super(); // register
		synchronized (db_lock) {
			lock();
			//check for data
			if (null == this.db_host || null == this.db_port || null == this.db_name || null == this.db_user || null == this.db_pw) {
				GenericDialog gd = new GenericDialog("Login");
				gd.addStringField("host: ", db_host);
				gd.addStringField("port: ", db_port);
				gd.addStringField("database name: ", db_name);
				gd.addStringField("user name: ", db_user);
				gd.addStringField("password: ", db_pw);
				Vector v = gd.getStringFields();
				java.awt.TextField tf = (java.awt.TextField)v.get(v.size() -1);
				tf.setEchoChar('*');
				gd.showDialog();
				if (gd.wasCanceled()) {
					unlock();
					return;
				}
				db_host = gd.getNextString();
				db_port = gd.getNextString();
				db_name = gd.getNextString();
				db_user = gd.getNextString();
				db_pw = gd.getNextString();
			}

			// load the JDBC driver for PostgreSQL, only if not already loaded
			if (!driver_loaded) {
				try {
					Class.forName("org.postgresql.Driver"); // NEVER modify this line.
					//Class.forName(Driver.getClass().getName()); // Does NOT work this way.
					driver_loaded = true;
				} catch (ClassNotFoundException cnfe) {
					driver_loaded = false;
					Utils.log("Loader: could not load "+Driver.class.getName()+": " + cnfe);
					unlock();
					return;
				}
			}

			if (!connectToDatabase()) {
				unlock();
				return;
			}

			// check database sanity: create tables if missing
			try {
				// 1 - check that the necessary tables exists, or create them otherwise
				String query0 = "SELECT relname FROM pg_class WHERE relname LIKE 'ab\\_%'"; // TODO WARNING the escape behavior is changing from 8.1.* to 8.2 ! In 8.2 then I should use \\\\ (I think, because java parses two of them)
				ResultSet result0 = connection.prepareStatement(query0).executeQuery();
				boolean table_projects_exists = false;
				boolean table_layers_exists = false;
				boolean table_layer_sets_exists = false;
				boolean table_displayables_exists = false;
				boolean table_patches_exists = false;
				boolean table_profiles_exists = false;
				boolean sequence_ids_exists = false;
				boolean table_displays_exists = false;
				boolean table_links_exists = false;
				boolean table_things_exists = false;
				boolean table_attributes_exists = false;
				boolean table_zdisplayables_exists = false;
				boolean table_pipe_points_exists = false;
				boolean table_labels_exists = false;
				boolean table_ball_points_exists = false;
				boolean table_area_paths_exists = false;
				// I'm missing php associative arrays and python dictionaries .. (Hashtables are way too absurdly hard to used effectively) (I'm missing typeless variables for that matter)
				while (result0.next()) {
					String relname = result0.getString("relname");
					if (relname.equals("ab_projects")) {
						table_projects_exists = true;
					} else if (relname.equals("ab_layers")) {
						table_layers_exists = true;
					} else if (relname.equals("ab_layer_sets")) {
						table_layer_sets_exists = true;
					} else if (relname.equals("ab_displayables")) {
						table_displayables_exists = true;
					} else if (relname.equals("ab_patches")) {
						table_patches_exists = true;
					} else if (relname.equals("ab_profiles")) {
						table_profiles_exists = true;
					} else if (relname.equals("ab_ids")) {
						sequence_ids_exists = true;
					} else if (relname.equals("ab_displays")) {
						table_displays_exists = true;
					} else if (relname.equals("ab_links")) {
						table_links_exists = true;
					} else if (relname.equals("ab_things")) {
						table_things_exists = true;
					} else if (relname.equals("ab_attributes")) {
						table_attributes_exists = true;
					} else if (relname.equals("ab_zdisplayables")) {
						table_zdisplayables_exists = true;
					} else if (relname.equals("ab_pipe_points")) {
						table_pipe_points_exists = true;
					} else if (relname.equals("ab_labels")) {
						table_labels_exists = true;
					} else if (relname.equals("ab_ball_points")) {
						table_ball_points_exists = true;
					} else if (relname.equals("ab_area_paths")) {
						table_area_paths_exists = true;
					}
				}
				result0.close();

				// not setup as batches because upgrading will depend on some tables existing or not existing

				// create table ab_projects if it does not exist
				if (!table_projects_exists) {
					//create table_projects:
					String query_projects = "CREATE TABLE ab_projects (id BIGINT NOT NULL, title TEXT NULL, trakem2_version TEXT NOT NULL, PRIMARY KEY (id))";
					connection.prepareStatement(query_projects).execute();
					Utils.log("Created table ab_projects in database " + db_name);
				} else {
					unlock(); // to enable inserts
					if (!upgradeProjectsTable()) {
						Utils.showMessage("Can't proceed without an upgraded 'ab_projects' table");
						return;
					}
					lock();
				}
				// create table ab_layers if it does not exist
				if (!table_layers_exists) {
					String query_layers = "CREATE TABLE ab_layers (id BIGINT NOT NULL, project_id BIGINT NOT NULL, layer_set_id BIGINT, z DOUBLE PRECISION NOT NULL, thickness DOUBLE PRECISION NOT NULL, PRIMARY KEY (id))";
					connection.prepareStatement(query_layers).execute();
					Utils.log("Created table ab_layers in database " + db_name);
				}
				// create table ab_layer_sets if it does not exist
				if (!table_layer_sets_exists) {
					String query_layer_sets = "CREATE TABLE ab_layer_sets (id BIGINT NOT NULL, project_id BIGINT NOT NULL, parent_layer_id BIGINT NOT NULL, active_layer_id BIGINT NOT NULL, layer_width DOUBLE PRECISION NOT NULL, layer_height DOUBLE PRECISION NOT NULL, rot_x DOUBLE PRECISION NOT NULL, rot_y DOUBLE PRECISION NOT NULL, rot_z DOUBLE PRECISION NOT NULL, snapshots_enabled BOOLEAN DEFAULT TRUE, PRIMARY KEY (id))";
					connection.prepareStatement(query_layer_sets).execute();
					Utils.log("Created table ab_layer_sets in database " + db_name);
				} else {
					if (!upgradeLayerSetTable()) {
						Utils.showMessage("Can't proceed without an upgraded 'ab_layer_sets' table.");
						return;
					}
				}
				// create table ab_displayables if it does not exist
				if (!table_displayables_exists) {
					String query_displayables = "CREATE TABLE ab_displayables (id BIGINT NOT NULL, layer_id BIGINT DEFAULT -1, title TEXT NOT NULL, x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, width DOUBLE PRECISION NOT NULL, height DOUBLE PRECISION NOT NULL, alpha DOUBLE PRECISION DEFAULT 1.0, visible BOOLEAN DEFAULT TRUE, color_red INT DEFAULT 255, color_green INT DEFAULT 255, color_blue INT DEFAULT 0, rot DOUBLE PRECISION DEFAULT 0.0, stack_index INT DEFAULT -1, annotation TEXT NULL, locked BOOLEAN DEFAULT FALSE, PRIMARY KEY (id))";
					connection.prepareStatement(query_displayables).execute();
					Utils.log("Created table ab_displayables in database " + db_name);
				} else {
					if (!upgradeDisplayablesTable()) {
						Utils.showMessage("Can't proceed without an upgraded 'ab_displayables' table");
						return;
					}
				}
				// create table ab_patches if it does not exist
				if (!table_patches_exists) {
					String query_patches = "CREATE TABLE ab_patches (id BIGINT NOT NULL, imp_type INT NOT NULL, tiff_original BYTEA, tiff_working BYTEA, tiff_snapshot BYTEA, min DOUBLE PRECISION DEFAULT -1, max DOUBLE PRECISION DEFAULT -1, PRIMARY KEY (id))";
					connection.prepareStatement(query_patches).execute();
					Utils.log("Created table ab_patches in database " + db_name);
				} else {
					if (!upgradePatchesTable()) {
						Utils.showMessage("Can't proceed without an upgraded 'ab_patches' table");
						return;
					}
				}
				// create table ab_profiles if it does not exist
				if (!table_profiles_exists) {
					String query_profiles = "CREATE TABLE ab_profiles (id BIGINT NOT NULL, polygon POLYGON NULL, closed BOOLEAN DEFAULT FALSE, PRIMARY KEY(id))";
					connection.prepareStatement(query_profiles).execute();
					Utils.log("Created table ab_profiles in database " + db_name);
				}
				// create sequence ab_ids if it does not exist
				if (!sequence_ids_exists) {
					String query_ids = "CREATE SEQUENCE ab_ids INCREMENT BY 1 NO CYCLE";
					connection.prepareStatement(query_ids).execute();
					Utils.log("Created sequence ab_ids in database " + db_name);
				}
				// create table ab_displays if it does not exist
				if (!table_displays_exists) {
					String query_displays = "CREATE TABLE ab_displays (id BIGINT NOT NULL, layer_id BIGINT NOT NULL, active_displayable_id BIGINT DEFAULT -1, window_x INT DEFAULT 0, window_y INT DEFAULT 0, magnification DOUBLE PRECISION DEFAULT 1.0, srcrect_x INT NULL, srcrect_y INT NULL, srcrect_width INT NULL, srcrect_height INT NULL, c_alphas INT DEFAULT " + (0xffffffff) + ", c_alphas_state INT DEFAULT " +(0xffffffff) + ", scroll_step INT DEFAULT 1, PRIMARY KEY (id))";
					connection.prepareStatement(query_displays).execute();
					Utils.log("Created table ab_displays in database " + db_name);
				} else {
					if (!upgradeDisplaysTable()) {
						Utils.showMessage("Can't proceed without an upgraded 'ab_displays' table");
						return;
					}
				}
				// create table ab_links if it does not exist
				if (!table_links_exists) {
					String query_links = "CREATE TABLE ab_links (project_id BIGINT NOT NULL, id1 BIGINT NOT NULL, id2 BIGINT NOT NULL)"; // no primary key
					connection.prepareStatement(query_links).execute();
					Utils.log("Created table ab_links in database " + db_name);
				}
				// create table ab_things if it does not exist
				if (!table_things_exists) {
					String query_things = "CREATE TABLE ab_things (id BIGINT NOT NULL, project_id BIGINT NOT NULL, type TEXT NOT NULL, title TEXT NULL, parent_id BIGINT NOT NULL, object_id BIGINT NOT NULL, PRIMARY KEY (id))"; // the 'title' column can be null. A -1 in the template_id indicates that the thing is a template in itself.
					connection.prepareStatement(query_things).execute();
					Utils.log("Created table ab_things in database " + db_name);
				} else {
					if (!upgradeThingsTable()) {
						Utils.showMessage("Can't proceed without updating template types for layer and layer_set.");
						return;
					}
				}
				// create table ab_attributes if it does not exist
				if (!table_attributes_exists) {
					String query_attributes = "CREATE TABLE ab_attributes (id BIGINT NOT NULL, thing_id BIGINT NOT NULL, name TEXT NOT NULL, value TEXT NULL, PRIMARY KEY (id))";
					connection.prepareStatement(query_attributes).execute();
					Utils.log("Created table ab_attributes in database " + db_name);
				}
				// create table ab_zdisplayables if it does not exist
				if (!table_zdisplayables_exists) {
					connection.prepareStatement("CREATE TABLE ab_zdisplayables (id BIGINT, project_id BIGINT NOT NULL, layer_set_id BIGINT, PRIMARY KEY(id))").execute();
					Utils.log("Created table ab_zdisplayables in database " + db_name);
				}
				// create table ab_pipe_points if it does not exist
				if (!table_pipe_points_exists) {
					connection.prepareStatement("CREATE TABLE ab_pipe_points (pipe_id BIGINT NOT NULL, index INT NOT NULL, x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, x_r DOUBLE PRECISION NOT NULL, y_r DOUBLE PRECISION NOT NULL, x_l DOUBLE PRECISION NOT NULL, y_l DOUBLE PRECISION NOT NULL, width DOUBLE PRECISION NOT NULL, layer_id BIGINT NOT NULL)").execute();
					Utils.log("Created table ab_pipe_points in database " + db_name);
				}
				// create table ab_labels if it does not exist
				if (!table_labels_exists) {
					connection.prepareStatement("CREATE TABLE ab_labels (id BIGINT NOT NULL, type TEXT, font_name TEXT, font_style INT, font_size INT, PRIMARY KEY (id))").execute();
					Utils.log("Created table ab_labels in database " + db_name);
				}
				// create table ab_ball_points if it does not exist
				if (!table_ball_points_exists) {
					connection.prepareStatement("CREATE TABLE ab_ball_points (ball_id BIGINT NOT NULL, x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, width DOUBLE PRECISION NOT NULL, layer_id BIGINT NOT NULL)").execute();
					Utils.log("Created table ab_ball_points in database " + db_name);
				}
				// create table ab_area_paths if it does not exist
				if (!table_area_paths_exists) {
					connection.createStatement().execute("CREATE TABLE ab_area_paths (area_list_id BIGINT NOT NULL, layer_id BIGINT NOT NULL, polygon POLYGON NULL, fill_paint BOOLEAN DEFAULT false)");
				} else {
					if (!upgradeAreaListTable()) {
						Utils.showMessage("Can't continue without upgrading the ab_area_paths table");
						return;
					}
				}

			} catch (SQLException sqle) {
				unlock();
				Utils.log("Loader: Database problems, can't check and/or create tables.");
				disconnect();
				new IJError(sqle);
				return;
			} catch (Exception e) {
				unlock();
				Utils.log("Loader: Database problems, can't check tables!");
				disconnect();
				new IJError(e);
				return;
			}
			unlock();
		}
	}

	/** Release all memory and unregister itself. */
	public void destroy() {
		if (IJ.getInstance().quitting()) {
			if (isConnected()) disconnect();
			return; // no need to do anything else
		}
		super.destroy();
		if (isConnected()) disconnect();
		System.gc();
		//try { Thread.sleep(1000); } catch (Exception e) {}
		//System.gc(); // twice ..
		Utils.showStatus("");
	}

	/**Connect to the database using the settings collected at construction time, only if not connected already.*/
	private boolean connectToDatabase() {
		try {
			if (null == connection || connection.isClosed()) {
				Utils.showStatus("Connecting...", false);
				connection = DriverManager.getConnection("jdbc:postgresql:" + (db_host.equals("") || db_host.equals("localhost") ? "" : "//" + db_host + (db_port.equals("") ? "" : ":" + db_port + "/")) + db_name, db_user, db_pw);
				prepareStatements();
				if (null != connection) {
					if (null != monitor) monitor.quit(); // kill any previous monitors before launching a new one
					// TODO gets in the way of the GUI too much
					// monitor = new Monitor(connection);
					// monitor.start();
				}
				Utils.showStatus("", false);
			}
		} catch (SQLException sqle) {
			Utils.showStatus("");
			Utils.log("Loader: can't connect to database: " + sqle);
			Utils.showMessage("Database connection failed.");
			return false;
		}
		return true;
	}

	private Connection preloader_connection = null;

	/** Returns a connection to be used for loading objects in the background. */
	private Connection getPreloaderConnection() {
		try {
			if (null == preloader_connection || preloader_connection.isClosed()) {
				preloader_connection = DriverManager.getConnection("jdbc:postgresql:" + (db_host.equals("") || db_host.equals("localhost") ? "" : "//" + db_host + (db_port.equals("") ? "" : ":" + db_port + "/")) + db_name, db_user, db_pw);
			}
		} catch (SQLException e) {
			new IJError(e);
			return null;
		}
		return preloader_connection;
	}

	private PreparedStatement stmt_add_patch = null;
	private PreparedStatement stmt_update_snap = null;
	private PreparedStatement stmt_add_displayable = null;

	/** Used in combination with commitLargeUpdate() */
	public void startLargeUpdate() {
		try {
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			new IJError(e);
		}
	}
	/** Used in combination with startLargeUpdate() */
	public void commitLargeUpdate() {
		try {
			connection.commit();
			connection.setAutoCommit(true);
		} catch (SQLException e) {
			new IJError(e);
		}
	}

	/** Used when errors ocurr during a large insertion. */
	public void rollback() {
		try {
			connection.rollback();
			connection.setAutoCommit(true);
		} catch (SQLException e) {
			new IJError(e);
		}
	}

	/** Prepare widely used statements. */
	private void prepareStatements() {
		try {
			this.stmt_add_patch = connection.prepareStatement("INSERT INTO ab_patches (id, imp_type, tiff_original, min, max) VALUES (?,?,?,?,?)");
			this.stmt_add_displayable = connection.prepareStatement("INSERT INTO ab_displayables (id, title, x, y, width, height) VALUES (?,?,?,?,?,?)");
			this.stmt_update_snap = connection.prepareStatement("UPDATE ab_patches SET tiff_snapshot=? WHERE id=?");
		} catch (SQLException e) {
			new IJError(e);
		}
	}

	/**Find out whether the connection is up. */
	public boolean isConnected() {
		synchronized (db_lock) {
			lock();
			try {
				if (null != connection && connection.isClosed()) {
					connection = null;
					unlock();
					return false;
				} else if (null == connection) {
					unlock();
					return false;
				}
			}catch(SQLException sqle) {
				new IJError(sqle);
				unlock();
				return false;
			}
			unlock();
			return true;
		}
	}

	/**Disconnect from the database. */
	public void disconnect() {
		synchronized (db_lock) {
			lock();
			try {
				if (null != connection) connection.close();
				//Utils.log("Loader: Disconnected.");
			} catch (SQLException sqle) {
				Utils.log("Loader: Can't close connection to database:\n " + sqle);
				unlock();
				return;
			}
			unlock();
		}
	}

	/**Retrieve next id from a sequence for a new DBObject to be added.*/
	public long getNextId() {
		if (!connectToDatabase()) {
			Utils.log("Not connected and can't connect to database.");
			return -System.currentTimeMillis(); // an improbable negative id to be repeated, ensures uniqueness
		}
		synchronized (db_lock) {
			lock();
			long id = Long.MIN_VALUE;
			try {
				String query = "SELECT nextval('ab_ids')";
				ResultSet result = connection.prepareStatement(query).executeQuery();
				if (result.next()) {
					id = result.getLong(1); //from the first and only column
				}
				result.close();
			} catch (SQLException sqle) {
				new IJError(sqle);
			}
			unlock();
			return id;
		}
	}

	/** Used to upgrade old databases. */
	private boolean upgradeProjectsTable() throws Exception {
		// Upgrade database if necessary: set a version field, create the TemplateThing entries in the database for each project from its XML template file, and delete the xml_template column
		// Check columns: see if trakem2_version is there
		ResultSet r = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_name='ab_projects' AND column_name='xml_template'").executeQuery();
		if (r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table projects.\nNo data will be lost, but reorganized.\nProceed?");
			if (!yn.yesPressed()) {
				return false;
			}
			// retrieve and parse XML template from each project
			ResultSet r1 = connection.prepareStatement("SELECT * FROM ab_projects").executeQuery();
			while (r1.next()) {
				long project_id = r1.getLong("id");
				// parse the XML file stored in the db and save the TemplateThing into the ab_things table
				InputStream xml_stream = null;
				try {
					String query = "SELECT xml_template FROM ab_projects WHERE id=" + project_id;
					ResultSet result = connection.prepareStatement(query).executeQuery();
					if (result.next()) {
						xml_stream = result.getBinaryStream("xml_template");
					}
					result.close();
				} catch (Exception e) {
					new IJError(e);
					return false;
				}
				if (null == xml_stream) {
					Utils.showMessage("Failed to upgrade the database schema: XML template stream is null.");
					return false;
				}
				TemplateThing template_root = new TrakEM2MLParser(xml_stream).getTemplateRoot();
				if (null == template_root) {
					Utils.showMessage("Failed to upgrade the database schema: root TemplateThing is null.");
					return false;
				}
				Project project = new Project(project_id, r1.getString("title"));
				project.setTempLoader(this);
				template_root.addToDatabase(project);
			}
			r1.close();
			// remove the XML column
			connection.prepareStatement("ALTER TABLE ab_projects DROP xml_template").execute();
			// org.postgresql.util.PSQLException: ERROR: adding columns with defaults is not implemented in 7.4.* (only in 8.1.4+)
			// connection.prepareStatement("ALTER TABLE ab_projects ADD version text default '" + Utils.version + "'").execute();
			// so: workaround
			connection.prepareStatement("ALTER TABLE ab_projects ADD version TEXT").execute();
			connection.prepareStatement("ALTER TABLE ab_projects ALTER COLUMN version SET DEFAULT '" + Utils.version + "'").execute();
		}
		r.close();

		return true; // success!
	}

	private boolean upgradeDisplayablesTable() throws Exception {
		ResultSet r = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_name='ab_displayables' AND column_name='locked'").executeQuery();
		if (!r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_displayables.\nThe column 'locked' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			connection.prepareStatement("ALTER TABLE ab_displayables ADD locked BOOLEAN").execute();
			connection.prepareStatement("ALTER TABLE ab_displayables ALTER COLUMN locked SET DEFAULT false").execute();
		}
		r.close();
		return true;
	}

	private boolean upgradeThingsTable() throws Exception {
		ResultSet r = connection.prepareStatement("SELECT type FROM ab_things WHERE type='Layer' or type='Layer Set'").executeQuery();
		if (r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_things.\nWill replace 'Layer Set' by 'layer_set'\nand 'Layer' by 'layer'.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			connection.prepareStatement("UPDATE ab_things SET type='layer' WHERE type='Layer'").executeUpdate();
			connection.prepareStatement("UPDATE ab_things SET type='layer_set' WHERE type='Layer Set'").executeUpdate();
		}
		r.close();
		ResultSet r2 = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_name='ab_things' AND column_name='expanded'").executeQuery();
		if (!r2.next()) { // if not found, add it
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_things.\nThe column 'expanded' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r2.close();
				return false;
			}
			connection.prepareStatement("ALTER TABLE ab_things ADD expanded BOOLEAN").execute();
			connection.prepareStatement("ALTER TABLE ab_things ALTER COLUMN expanded SET DEFAULT true").execute();
		}
		r2.close();
		return true;
	}

	private boolean upgradeLayerSetTable() throws Exception {
		final Statement st = connection.createStatement();
		ResultSet r = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='ab_layer_sets' AND column_name='snapshots_enabled'");
		if (!r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_layer_sets.\nThe column 'snapshots_enabled' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			st.execute("ALTER TABLE ab_layer_sets ADD snapshots_enabled BOOLEAN");
			st.execute("ALTER TABLE ab_layer_sets ALTER COLUMN snapshots_enabled SET DEFAULT true");
		}
		r.close();
		return true;
	}
	private boolean upgradeDisplaysTable() throws Exception {
		ResultSet r = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_name='ab_displays' AND column_name='scroll_step'").executeQuery();
		if (!r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_displays.\nThe column 'scroll_step' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			connection.prepareStatement("ALTER TABLE ab_displays ADD scroll_step INT").execute();
			connection.prepareStatement("ALTER TABLE ab_displays ALTER COLUMN scroll_step SET DEFAULT 1").execute();
			// in two rows, so that 7.4.* is still supported.
		}
		r.close();
		return true;
	}

	private boolean upgradeAreaListTable() throws Exception {
		ResultSet r = connection.createStatement().executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='ab_area_paths' AND column_name='fill_paint'");
		if (!r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade table ab_area_paths.\nThe column 'fill_paint' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			connection.createStatement().executeUpdate("ALTER TABLE ab_area_paths ADD fill_paint BOOLEAN");
			connection.createStatement().executeUpdate("ALTER TABLE ab_area_paths ALTER COLUMN fill_paint SET DEFAULT TRUE");
		}
		r.close();
		return true;
	}

	private boolean upgradePatchesTable() throws Exception {
		Statement st = connection.createStatement();
		ResultSet r = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='ab_patches' AND column_name='min'");
		if (!r.next()) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Upgrade", "Need to upgrade the table ab_patches.\nThe columns 'min' and 'max' will be added.\nProceed?");
			if (!yn.yesPressed()) {
				r.close();
				return false;
			}
			st.executeUpdate("ALTER TABLE ab_patches ADD min DOUBLE PRECISION");
			st.executeUpdate("ALTER TABLE ab_patches ALTER COLUMN min SET DEFAULT -1");
			st.executeUpdate("ALTER TABLE ab_patches ADD max DOUBLE PRECISION");
			st.executeUpdate("ALTER TABLE ab_patches ALTER COLUMN max SET DEFAULT -1");
		}
		r.close();
		return true;
	}



	/** Fetch the root of the TemplateThing tree from the database-stored hierarchy of TemplateThing objects defined in the original XML file .*/
	public TemplateThing getTemplateRoot(Project project) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return null;
		}
		TemplateThing root = null;
		synchronized (db_lock) {
			/*
			InputStream xml_stream = null;
			try {
				String query = "SELECT xml_template FROM ab_projects WHERE id=" + project.getId();
				ResultSet result = connection.prepareStatement(query).executeQuery();
				if (result.next()) {
					xml_stream = result.getBinaryStream("xml_template");
				}
				result.close();
			} catch (SQLException sqle) {
				new IJError(sqle);
				return null;
			} catch (Exception e) {
				new IJError(e);
				return null;
			}
			// make a TemplateTree from the XML file stream
			if (null == xml_stream) {
				return null;
			}
			root = new TrakEM2MLParser(xml_stream).getTemplateRoot();
			if (null == root) {
				return null;
			}
			return root;
			*/

			// New way: TemplateThing instances are saved in the ab_things table
			lock();
			try {
				// fetch TemplateThings, which have no stored object.
				ResultSet r = connection.prepareStatement("SELECT * FROM ab_things WHERE project_id=" + project.getId() + " AND parent_id=-1 AND object_id=-1").executeQuery(); // signature of the root TemplateThing is parent_id=-1 and object_id=-1
				if (r.next()) {
					long id = r.getLong("id");
					String type = r.getString("type");
					root = new TemplateThing(type, project, id);
					root.setup(getChildrenTemplateThings(project, id), getTemplateAttributes(project, id));
				}
				r.close();
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
		}
		return root;
	}

	/** Recursive into children. */
	private ArrayList getChildrenTemplateThings(Project project, long parent_id) throws Exception {
		ArrayList al = new ArrayList();
		ResultSet r = connection.prepareStatement("SELECT * FROM ab_things WHERE parent_id=" + parent_id).executeQuery();
		while (r.next()) {
			long id = r.getLong("id");
			String type = r.getString("type");
			TemplateThing tt = new TemplateThing(type, project, id);
			tt.setup(getChildrenTemplateThings(project, id), getTemplateAttributes(project, id));
			al.add(tt);
		}
		r.close();
		return al;
	}

	// this method is a copy of the getProjectAttributes ... could pass a Constructor as argument and have only one method then.
	private Hashtable getTemplateAttributes(Project project, long thing_id) throws Exception {
		Hashtable ht = new Hashtable();
		ResultSet r = connection.prepareStatement("SELECT * FROM ab_attributes WHERE thing_id=" + thing_id).executeQuery();
		while (r.next()) {
			String name = r.getString("name");
			ht.put(name, new TemplateAttribute(name, r.getString("value"), project, r.getLong("id")));
		}
		r.close();
		return ht;
	}

	/** Fetch all existing projects from the database. */
	public Project[] getProjects() {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}
			Project[] projects = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT title, id FROM ab_projects ORDER BY id").executeQuery();
				ArrayList al_projects = new ArrayList();
				while (r.next()) {
					al_projects.add(new Project(r.getLong("id"), r.getString("title")));
				}
				r.close();
				projects = new Project[al_projects.size()];
				al_projects.toArray(projects);
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return projects;
		}
	}

	/** Recursive. Assumes all TemplateThing objects have a unique type. */
	private void unpack(TemplateThing root, Hashtable hs_tt) {
		String type = root.getType();
		if (null != hs_tt.get(type)) return; // avoid replacing, the higher level one is the right one (for example for neurite_branch)
		hs_tt.put(type, root);
		if (null == root.getChildren()) return;
		Iterator it = root.getChildren().iterator();
		while (it.hasNext()) {
			TemplateThing tt = (TemplateThing)it.next();
			unpack(tt, hs_tt);
		}
	}

	/** Get all the Thing objects, recursively, for the root, and their corresponding encapsulated objects. Also, fills in the given ArrayList with all loaded Displayable objects. */
	public ProjectThing getRootProjectThing(Project project, TemplateThing root_tt, TemplateThing project_tt, Hashtable hs_d) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}
			// unpack root_tt (assumes TemplateThing objects have unique types, skips any repeated type to avoid problems in recusive things such as neurite_branch)
			Hashtable hs_tt = new Hashtable();
			unpack(root_tt, hs_tt);

			ProjectThing root = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT * FROM ab_things WHERE project_id=" + project.getId() + " AND type='project' AND parent_id=-1").executeQuery(); // -1 signals root
				if (r.next()) {
					long id = r.getLong("id");
					root = new ProjectThing(project_tt, project, id, project, getChildrenProjectThings(project, id, project_tt.getType(), hs_tt, hs_d), getProjectAttributes(project, id));
				}
				r.close();
				if (null == root) {
					Utils.log("Loader.getRootProjectThing: can't find it for project id=" + project.getId());
					unlock();
					return null;
				}
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return root;
		}
	}

	private ProjectThing getProjectThing(ResultSet r, Project project, Hashtable hs_tt, Hashtable hs_d) throws Exception {
		long id = r.getLong("id");
		String type = r.getString("type");
		TemplateThing tt = (TemplateThing)hs_tt.get(type);
		if (null == tt) {
			Utils.log("Loader.getProjectThing: can not find a proper TemplateThing of type " + type + " for the ProjectThing of id=" + id);
			return null;
		}
		long object_id = r.getLong("object_id");
		Object ob = r.getString("title"); // may be null
		if (-1 != object_id) {
			ob = getProjectObject(project, object_id);
			if (ob instanceof Displayable) hs_d.put(new Long(((DBObject)ob).getId()), ob);
			else Utils.log("Loader.getProjectThing: not adding to hs_d: " + ob);
		}
		return new ProjectThing(tt, project, id, ob, getChildrenProjectThings(project, id, type, hs_tt, hs_d), getProjectAttributes(project, id));
	}

	private ArrayList getChildrenProjectThings(Project project, long parent_id, String parent_type, Hashtable hs_tt, Hashtable hs_d) throws Exception {
		ArrayList al_children = new ArrayList();
		ResultSet r = null;
		if (-1 == parent_id) Utils.log("parent_id = -1 for parent_type=" + parent_type);
		if (parent_type.equals("profile_list")) {
			r = connection.prepareStatement("SELECT ab_things.* FROM ab_things,ab_displayables,ab_layers WHERE ab_things.parent_id=" + parent_id + " AND ab_things.object_id=ab_displayables.id AND ab_displayables.layer_id=ab_layers.id ORDER BY ab_layers.z,ab_things.id ASC").executeQuery();  // the project_id field is redundant
		} else {
			r = connection.prepareStatement("SELECT * FROM ab_things WHERE parent_id=" + parent_id + " ORDER BY id").executeQuery();
		}
		while (r.next()) {
			ProjectThing thing = getProjectThing(r, project, hs_tt, hs_d);
			if (null != thing) al_children.add(thing);
		}
		r.close();
		return al_children;
	}


	/** Fetch all attributes for the given Thing id. Returns an empty Hashtable if none.*/
	private Hashtable getProjectAttributes(Project project, long thing_id) throws Exception {
		Hashtable hs = new Hashtable();
		ResultSet r = connection.prepareStatement("SELECT * FROM ab_attributes WHERE thing_id=" + thing_id).executeQuery();
		while (r.next()) {
			String name = r.getString("name");
			hs.put(name, new ProjectAttribute(project, r.getLong("id"), name, r.getString("value"))); // null owner, can't eat his own tail. And the object is a temporary String with the value, which can be an id, etc. and will be resolved with a call to setup(Thing owner)
		}
		r.close();
		return hs;
	}

	/** Fetch the object if any, which means, if the id corresponds to a basic object, that object will be minimally constructed to be usable and returned. */
	private Object getProjectObject(Project project, long id) throws Exception {
		// THE CAN OF WORMS!
		if (-1 == id) return null; // meaning, the ProjectThing is high level, so it doesn't have any Displayable object directly.

		Object object = null;

		// Try all posible tables (this can certainly be optimized by adding a table of ids vs table names, and then making a joint call)
		object = fetchProfile(project, id);
		if (null != object) return object;
		object = fetchPipe(project, id);
		if (null != object) return object;
		object = fetchBall(project, id);
		if (null != object) return object;
		object = fetchAreaList(project, id);
		if (null != object) return object;

		// finally:
		if (null == object) {
			Utils.log("Loader.makeObject: don't know what to do with object of id=" + id);
		}
		return object;
	}

	private Profile fetchProfile(Project project, long id) throws Exception {
		// joint call
		ResultSet r = connection.prepareStatement("SELECT ab_profiles.id, ab_displayables.id, title, x, y, width, height, alpha, visible, color_red, color_green, color_blue, closed, locked FROM ab_profiles, ab_displayables WHERE ab_profiles.id=ab_displayables.id AND ab_profiles.id=" + id).executeQuery();
		Profile p = null;
		if (r.next()) {
			p = new Profile(project, id, r.getString("title"), r.getDouble("x"), r.getDouble("y"), r.getDouble("width"), r.getDouble("height"), (float)r.getDouble("alpha"), r.getBoolean("visible"), new Color(r.getInt("color_red"), r.getInt("color_green"), r.getInt("color_blue")), r.getBoolean("closed"), r.getBoolean("locked"));
			// the polygon is not loaded, only when repainting the profile.
		}
		r.close();
		return p;
	}

	private Pipe fetchPipe(Project project, long id) throws Exception {
		ResultSet r = connection.prepareStatement("SELECT ab_displayables.id, title, ab_displayables.x, ab_displayables.y, ab_displayables.width, height, alpha, visible, color_red, color_green, color_blue, ab_zdisplayables.id, ab_pipe_points.pipe_id, ab_displayables.locked FROM ab_zdisplayables, ab_displayables, ab_pipe_points WHERE ab_zdisplayables.id=ab_displayables.id AND ab_zdisplayables.id=ab_pipe_points.pipe_id AND ab_zdisplayables.id=" + id).executeQuery(); // strange query, but can't distinguish between pipes and balls otherwise
		Pipe p = null;
		if (r.next()) {
			p = new Pipe(project, id, r.getString("title"), r.getDouble("x"), r.getDouble("y"), r.getDouble("width"), r.getDouble("height"), r.getFloat("alpha"), r.getBoolean("visible"), new Color(r.getInt("color_red"), r.getInt("color_green"), r.getInt("color_blue")), r.getBoolean("locked"));
		}
		r.close();
		return p;
	}

	private Ball fetchBall(Project project, long id) throws Exception {
		ResultSet r = connection.prepareStatement("SELECT ab_displayables.id, title, ab_displayables.x, ab_displayables.y, ab_displayables.width, height, alpha, visible, color_red, color_green, color_blue, ab_zdisplayables.id, ab_ball_points.ball_id, ab_displayables.locked FROM ab_zdisplayables, ab_displayables, ab_ball_points WHERE ab_zdisplayables.id=ab_displayables.id AND ab_zdisplayables.id=ab_ball_points.ball_id AND ab_zdisplayables.id=" + id).executeQuery(); // strange query, but can't distinguish between pipes and balls otherwise
		Ball b = null;
		if (r.next()) {
			b = new Ball(project, id, r.getString("title"), r.getDouble("x"), r.getDouble("y"), r.getDouble("width"), r.getDouble("height"), r.getFloat("alpha"), r.getBoolean("visible"), new Color(r.getInt("color_red"), r.getInt("color_green"), r.getInt("color_blue")), r.getBoolean("locked"));
		}
		r.close();
		return b;
	}

	private AreaList fetchAreaList(Project project, long id) throws Exception {
		ResultSet r = connection.createStatement().executeQuery("SELECT area_list_id, layer_id FROM ab_area_paths WHERE area_list_id=" + id);
		ArrayList al_ul = new ArrayList();
		while (r.next()) {
			al_ul.add(new Long(r.getLong(2))); // the ids of the unloaded layers
		}
		r.close();
		r = connection.prepareStatement("SELECT ab_displayables.id, title, ab_displayables.x, ab_displayables.y, ab_displayables.width, height, alpha, visible, color_red, color_green, color_blue, locked, ab_zdisplayables.id FROM ab_zdisplayables, ab_displayables WHERE ab_zdisplayables.id=ab_displayables.id AND ab_zdisplayables.id=" + id).executeQuery();
		AreaList area_list = null;
		if (r.next()) {
			area_list = new AreaList(project, id, r.getString("title"), r.getDouble("x"), r.getDouble("y"), r.getDouble("width"), r.getDouble("height"), r.getFloat("alpha"), r.getBoolean("visible"), new Color(r.getInt("color_red"), r.getInt("color_green"), r.getInt("color_blue")), r.getBoolean("locked"), al_ul);
		}
		r.close();
		return area_list;
	}

	/** Unpack all objects and accumulate them, tagged by their id. */
	private void unpack(ProjectThing root, Hashtable hs) {
		Object ob = root.getObject();
		if (null != ob && ob instanceof DBObject) {
			DBObject dbo = (DBObject)ob;
			hs.put(new Long(dbo.getId()), dbo);
		} else {
			//Utils.log("ProjectThing " + root + " has ob: " + ob);
		}
		if (null == root.getChildren()) return;
		Iterator it = root.getChildren().iterator();
		while (it.hasNext()) {
			ProjectThing pt = (ProjectThing)it.next();
			unpack(pt, hs);
		}
	}

	/** Fetches the root LayerSet, fills it with children (recursively) and uses the profiles, pipes, etc., from the project_thing. Will reconnect the links and open Displays for the layers that have one. */
	public LayerThing getRootLayerThing(Project project, ProjectThing project_thing, TemplateThing layer_set_tt, TemplateThing layer_tt) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}
			Hashtable hs_pt = new Hashtable();
			unpack(project_thing, hs_pt);

			LayerThing root = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT * FROM ab_things WHERE project_id=" + project.getId() + " AND type='layer_set' AND parent_id=-1").executeQuery(); // -1 signals root
				if (r.next()) {
					root = getLayerThing(r, project, hs_pt, layer_set_tt, layer_tt);
				}
				r.close();
				if (null == root) {
					Utils.log("Loader.getRootLayerThing: can't find it for project id=" + project.getId());
					unlock();
					return null;
				}

				// Redo the links! hs_pt contains now all Displayable objects.
				ResultSet rl = connection.prepareStatement("SELECT * FROM ab_links WHERE project_id=" + project.getId()).executeQuery();
				while (rl.next()) {
					Long id1 = new Long(rl.getLong("id1"));
					Long id2 = new Long(rl.getLong("id2"));
					Object ob1 = hs_pt.get(id1);
					Object ob2 = hs_pt.get(id2);
					if (null != ob1 && null != ob2) {
						Displayable d = (Displayable)ob1;
						d.link((Displayable)ob2, false);
					} else {
						Utils.log("Loader: broken link between " + id1 + " and " + id2);
					}
				}
				rl.close();
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return root;
		}
	}

	private LayerThing getLayerThing(ResultSet r, Project project, Hashtable hs_pt, TemplateThing layer_set_tt, TemplateThing layer_tt) throws Exception {
		long id = r.getLong("id");
		String type = r.getString("type");
		TemplateThing template = type.equals("layer_set") ? layer_set_tt : layer_tt; // if not a "Layer", then it's a "Layer Set"
		return new LayerThing(template, project, id, r.getString("title"), getLayerThingObject(project, r.getLong("object_id"), template, hs_pt), getChildrenLayerThing(project, id, hs_pt, layer_set_tt, layer_tt), getLayerAttributes(project, id)); // HERE the order of the arguments layer_set_tt and layer_tt was inverted, and it worked??? There was a compensating bug, incredibly enough, in the type.equals(.. above.
	}

	private ArrayList getChildrenLayerThing(Project project, long parent_id, Hashtable hs_pt, TemplateThing layer_set_tt, TemplateThing layer_tt) throws Exception {
		ArrayList al_children = new ArrayList();
		ResultSet r = connection.prepareStatement("SELECT ab_things.id AS id, ab_layers.id AS layer_id, ab_layers.project_id AS l_project_id, ab_things.project_id AS project_id, type, title, parent_id, object_id, z FROM ab_things, ab_layers WHERE ab_things.project_id=ab_layers.project_id AND ab_things.object_id=ab_layers.id AND ab_things.parent_id=" + parent_id + " ORDER BY z ASC").executeQuery();
		while (r.next()) {
			al_children.add(getLayerThing(r, project, hs_pt, layer_set_tt, layer_tt));
		}
		r.close();
		return al_children;
	}

	private Object getLayerThingObject(Project project, long id, TemplateThing template, Hashtable hs_pt) throws Exception {
		if (template.getType().equals("layer")) {
			return fetchLayer(project, id, hs_pt);
		} else if (template.getType().equals("layer_set")) {
			// find the LayerSet in the hs_pt (where it has been added by a call to the fetchLayer
			Object ob = hs_pt.get(new Long(id));
			if (ob != null) return ob;
			else {
				// the very first, top level LayerSet, which belongs to no layer
				ResultSet rls = connection.prepareStatement("SELECT * FROM ab_layer_sets, ab_displayables WHERE ab_layer_sets.id=ab_displayables.id AND ab_layer_sets.id=" + id).executeQuery();
				LayerSet layer_set = null;
				if (rls.next()) {
					long ls_id = rls.getLong("id");
					layer_set = new LayerSet(project, ls_id, rls.getString("title"), rls.getDouble("x"), rls.getDouble("y"), rls.getDouble("width"), rls.getDouble("height"), rls.getDouble("rot_x"), rls.getDouble("rot_y"), rls.getDouble("rot_z"), rls.getDouble("layer_width"), rls.getDouble("layer_height"), rls.getBoolean("locked"), rls.getBoolean("snapshots_enabled"));
					// store for children Layer to find it
					hs_pt.put(new Long(ls_id), layer_set);
					// find the pipes (or other possible ZDisplayable objects) in the hs_pt that belong to this LayerSet and add them silently
					ResultSet rpi = connection.prepareStatement("SELECT ab_displayables.id, ab_zdisplayables.id, layer_id, layer_set_id, stack_index FROM ab_displayables,ab_zdisplayables WHERE ab_displayables.id=ab_zdisplayables.id AND layer_set_id=" + ls_id + " ORDER BY stack_index ASC").executeQuery();
					while (rpi.next()) {
						Long idd = new Long(rpi.getLong("id"));
						Object obb = hs_pt.get(idd);
						//Utils.log2("getLayerThingObject: obb=" + obb + "  id=" + idd + "  layer_set_id=" + layer_set.getId());
						if (null != obb && obb instanceof ZDisplayable) {
							layer_set.addSilently((ZDisplayable)obb);
						} else {
							Utils.log("getLayerThingObject: failed to add a ZDisplayable to the root layer_set: zdispl id = " + idd); // this can happen when objects exist in the database but there is no associated thing.
						}
					}
					rpi.close();
				}
				rls.close();
				return layer_set;
			}
		} else {
			Utils.log("Loader.getLayerThingObject: don't know what to do with a template of type " + template.getType());
			return null;
		}
	}

	/** Load all objects into the Layer: Profile and Pipe from the hs_pt (full of ProjectThing wrapping them), and Patch, LayerSet, DLabel, etc from the database. */
	private Layer fetchLayer(Project project, long id, Hashtable hs_pt) throws Exception {
		ResultSet r = connection.prepareStatement("SELECT * FROM ab_layers WHERE id=" + id).executeQuery();
		Layer layer = null;
		if (r.next()) {
			long layer_id = r.getLong("id");
			layer = new Layer(project, layer_id, r.getDouble("z"), r.getDouble("thickness"));
			// find the Layer's parent
			long parent_id = r.getLong("layer_set_id");
			Object set = hs_pt.get(new Long(parent_id));
			if (null != set) {
				((LayerSet)set).addSilently(layer);
			} else {
				Utils.log("Loader.fetchLayer: WARNING no parent for layer " + layer);
			}
			// add the displayables from hs_pt that correspond to this layer (and all other objects that belong to the layer)
			Hashtable hs_d = new Hashtable();

			ResultSet rd = connection.prepareStatement("SELECT ab_displayables.id, ab_profiles.id, layer_id, stack_index FROM ab_displayables,ab_profiles WHERE ab_displayables.id=ab_profiles.id AND layer_id=" + layer_id).executeQuery();
			while (rd.next()) {
				Long idd = new Long(rd.getLong("id"));
				Object ob = hs_pt.get(idd);
				//Utils.log("Found profile with id=" + idd + " and ob = " + ob);
				if (null != ob) {
					hs_d.put(new Integer(rd.getInt("stack_index")), ob);
				}
			}
			rd.close();

			// fetch LayerSet objects (which are also Displayable), and put them in the hs_pt (this is hackerous)
			ResultSet rls = connection.prepareStatement("SELECT * FROM ab_layer_sets, ab_displayables WHERE ab_layer_sets.id=ab_displayables.id AND ab_layer_sets.parent_layer_id=" + id).executeQuery();
			while (rls.next()) {
				long ls_id = rls.getLong("id");
				LayerSet layer_set = new LayerSet(project, ls_id, rls.getString("title"), rls.getDouble("x"), rls.getDouble("y"), rls.getDouble("width"), rls.getDouble("height"), rls.getDouble("rot_x"), rls.getDouble("rot_y"), rls.getDouble("rot_z"), rls.getDouble("layer_width"), rls.getDouble("layer_height"), rls.getBoolean("locked"), rls.getBoolean("snapshots_enabled"));
				hs_pt.put(new Long(ls_id), layer_set);
				hs_d.put(new Integer(rls.getInt("stack_index")), layer_set);
				layer_set.setLayer(layer, false);
				// find the pipes (or other possible ZDisplayable objects) in the hs_pt that belong to this LayerSet and add them silently
				ResultSet rpi = connection.prepareStatement("SELECT ab_displayables.id, ab_zdisplayables.id, layer_id, layer_set_id, stack_index FROM ab_displayables,ab_zdisplayables WHERE ab_displayables.id=ab_zdisplayables.id AND layer_set_id=" + ls_id + " ORDER BY stack_index ASC").executeQuery();
				while (rpi.next()) {
					Long idd = new Long(rpi.getLong("id"));
					Object ob = hs_pt.get(idd);
					if (null != ob && ob instanceof ZDisplayable) {
						layer_set.addSilently((ZDisplayable)ob);
					} else {
						Utils.log("fetchLayer: failed to add a ZDisplayable to the layer_set. zdispl id = " + idd);
					}
				}
				rpi.close();
			}
			rls.close();

			// add Patch objects from ab_patches joint-called with ab_displayables
			ResultSet rp = connection.prepareStatement("SELECT ab_patches.id, ab_displayables.id, layer_id, title, x, y, width, height, rot, stack_index, imp_type, locked FROM ab_patches,ab_displayables WHERE ab_patches.id=ab_displayables.id AND ab_displayables.layer_id=" + layer_id).executeQuery();
			while (rp.next()) {
				long patch_id = rp.getLong("id");
				Patch patch = new Patch(project, patch_id, rp.getString("title"), rp.getDouble("x"), rp.getDouble("y"), rp.getDouble("width"), rp.getDouble("height"), rp.getDouble("rot"), rp.getInt("imp_type"), rp.getBoolean("locked"), rp.getDouble("min"), rp.getDouble("max"));
				hs_pt.put(new Long(patch_id), patch); // collecting all Displayable objects to reconstruct links
				hs_d.put(new Integer(rp.getInt("stack_index")), patch);
			}
			rp.close();

			// add DLabel objects
			ResultSet rl = connection.prepareStatement("SELECT ab_labels.id, ab_displayables.id, layer_id, title, x, y, width, height, rot, stack_index, font_name, font_style, font_size, ab_labels.type, locked FROM ab_labels,ab_displayables WHERE ab_labels.id=ab_displayables.id AND ab_displayables.layer_id=" + layer_id).executeQuery();
			while (rl.next()) {
				long label_id = rl.getLong("id");
				DLabel label = new DLabel(project, label_id, rl.getString("title"), rl.getDouble("x"), rl.getDouble("y"), rl.getDouble("width"), rl.getDouble("height"), rl.getDouble("rot"), rl.getInt("type"), rl.getString("font_name"), rl.getInt("font_style"), rl.getInt("font_size"), rl.getBoolean("locked"));
				hs_pt.put(new Long(label_id), label); // collecting all Displayable objects to reconstruct links
				hs_d.put(new Integer(rl.getInt("stack_index")), label);
			}
			rl.close();

			// Add silently to the Layer ordered by stack index
			Enumeration e = hs_d.keys();
			Object[] si = new Object[hs_d.size()];
			int i = 0;
			while (e.hasMoreElements()) {
				si[i++] = e.nextElement();
			}
			Arrays.sort(si); // will it sort an array of integers correctly? Who knows!
			for (i=0; i<si.length; i++) {
				//Utils.log("Loader layer.addSilently: adding " + (DBObject)hs_d.get(si[i]));
				layer.addSilently((DBObject)hs_d.get(si[i]));
			}


			// find displays and open later, when fully loaded.
			ResultSet rdi = connection.prepareStatement("SELECT * FROM ab_displays WHERE layer_id=" + layer.getId()).executeQuery();
			while (rdi.next()) {
				fetchDisplay(rdi, layer);
			}
			rdi.close();
		}
		r.close();
		return layer;
	}

	private Hashtable getLayerAttributes(Project project, long thing_id) throws Exception {
		Hashtable hs = new Hashtable(); // I want jython NOW
		/*
		ResultSet r = connection.prepareStatement("SELECT * FROM ab_attributes WHERE thing_id=" + thing_id).executeQuery();
		while (r.next()) {
			// TODO
		}
		r.close();
		*/
		return hs; // TODO, layers have no attributes for now
	}

	private void fetchDisplay(ResultSet r, Layer layer) throws Exception {
		Object[] props = new Object[]{new Point(r.getInt("window_x"), r.getInt("window_y")), new Double(r.getDouble("magnification")), new Rectangle(r.getInt("srcrect_x"), r.getInt("srcrect_y"), r.getInt("srcrect_width"), r.getInt("srcrect_height")), new Long(r.getLong("active_displayable_id")), new Integer(r.getInt("c_alphas")), new Integer(r.getInt("c_alphas_state"))};
		new Display(layer.getProject(), r.getLong("id"), layer, props); // will open later, when signaled.
	}

	/** Get the bezier points from the database for the given profile but as a triple array of points, that is, three arrays with 2 arrays (x and y) each. */
	public double[][][] fetchBezierArrays(long id) {
		synchronized (db_lock) {
			lock();
			// TODO: cache! add/check

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}

			releaseMemory();

			PGpolygon p = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT id, polygon FROM ab_profiles WHERE id=" + id).executeQuery();
				if (r.next()) {
					p = (PGpolygon)r.getObject("polygon");
				}
				r.close();

			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return toBezierArrays(p);
		}
	}

	/** Split the PGpolygon into the 3 arrays (left control points, backbone points and right control points) from which it was made.*/
	private double[][][] toBezierArrays(final PGpolygon polygon) {
		if (null == polygon) return null;
		final int n_points = polygon.points.length / 3;
		final double[][][] b = new double[3][][];
		final double[][] p = new double[2][n_points];
		final double[][] p_l = new double[2][n_points];
		final double[][] p_r = new double[2][n_points];
		for (int i=0, j=0; i<n_points; i++, j+=3) {
			p_l[0][i] = polygon.points[j].x;
			p_l[1][i] = polygon.points[j].y;
			p[0][i] = polygon.points[j+1].x;
			p[1][i] = polygon.points[j+1].y;
			p_r[0][i] = polygon.points[j+2].x;
			p_r[1][i] = polygon.points[j+2].y;
		}
		b[0] = p_l;
		b[1] = p;
		b[2] = p_r;
		return b;
	}

	public Area fetchArea(long area_list_id, long layer_id) {
		synchronized(db_lock) {
			lock();

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}

			releaseMemory();

			Area area = new Area();
			try {
			ResultSet r = connection.createStatement().executeQuery(new StringBuffer("SELECT * from ab_area_paths WHERE area_list_id=").append(area_list_id).append(" AND layer_id=").append(layer_id).toString());
			while (r.next()) {
				PGpolygon pol = (PGpolygon)r.getObject("polygon");
				area.add(new Area(makePolygon(pol)));
			}
			r.close();
			} catch (Exception e) {
				new IJError(e);
				return null;
			}

			unlock();
			return area;
		}
	}

	public ArrayList fetchPipePoints(long id) {
		synchronized (db_lock) {
			lock();

			// TODO: cache! add/check

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}

			releaseMemory();

			ArrayList al = new ArrayList();
			try {
				ResultSet r = connection.prepareStatement("SELECT * FROM ab_pipe_points WHERE pipe_id=" + id + " ORDER BY index ASC").executeQuery();
				// Can't count rowns!! num_rows ??!!! stupid java
				while (r.next()) {
					al.add(new Object[]{
						r.getObject("x"),
						r.getObject("y"),
						r.getObject("x_r"),
						r.getObject("y_r"),
						r.getObject("x_l"),
						r.getObject("y_l"),
						r.getObject("width"),
						r.getObject("layer_id")
					});
				}
				r.close();
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return al;
		}
	}

	public ArrayList fetchBallPoints(long id) {
		synchronized (db_lock) {
			lock();
			// TODO: cache! add/check

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}

			releaseMemory();

			ArrayList al = new ArrayList();
			try {
				ResultSet r = connection.prepareStatement("SELECT * FROM ab_ball_points WHERE ball_id=" + id + " ORDER BY layer_id ASC").executeQuery();
				// Can't count rowns!! num_rows ??!!! stupid java
				while (r.next()) {
					al.add(new Object[]{
						r.getObject("x"),
						r.getObject("y"),
						r.getObject("width"),
						r.getObject("layer_id")
					});
				}
				r.close();
			} catch (Exception e) {
				new IJError(e);
				unlock();
				return null;
			}
			unlock();
			return al;
		}
	}

	public Image fetchSnapshot(Patch p) {
		synchronized (db_lock) {
			lock();
			// see if cached:
			long id = p.getId();
			Image snap = snaps.get(id);
			if (null != snap) {
				unlock();
				//Utils.log("method snap");
				return snap;
			}

			releaseMemory();

			// see if the Displayable AWT image is cached:
			Image awt = awts.get(id);
			if (null != awt) {
				Image image = Snapshot.createSnap(p, awt, Snapshot.SCALE); //awt.getScaledInstance((int)(p.getWidth() * Snapshot.SCALE), (int)(p.getHeight() * Snapshot.SCALE), Snapshot.SCALE_METHOD);
				snaps.put(id, image);
				unlock();
				//Utils.log("method awt");
				return image;
			}
			// see if the ImagePlus is cached:
			ImagePlus imp = imps.get(id);
			if (null != imp) {
				unlock();
				Image image = p.createImage(); // with adjusted channels, calls the ImagePlus
				lock();
				awts.put(id, image);
				image = Snapshot.createSnap(p, image, Snapshot.SCALE); //image.getScaledInstance((int)(p.getWidth() * Snapshot.SCALE), (int)(p.getHeight() * Snapshot.SCALE), Snapshot.SCALE_METHOD);
				snaps.put(id, image);

				unlock();
				//Utils.log("method imp");
				return image;
			}

			// finally, reload if it exists in the database:

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return NOT_FOUND;
			}

			Image image = null;
			InputStream i_stream = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT tiff_snapshot FROM ab_patches WHERE id=" + id).executeQuery();
				if (r.next()) {
					i_stream = r.getBinaryStream(1); //"tiff_snapshot");
					if (null != i_stream) { // else return null, will be made new.
						imp = unzipTiff(i_stream, "s");
						i_stream.close();
						image = imp.getProcessor().createImage(); // must be independent so the ImagePlus can be flushed (this is unnecessary by the way, it amounts to the same, the ImagePlus.flush() does not flush the awt.Image)
						snaps.put(id, image);
						//imp.flush(); // no need! Look at ij/ImagePlus.java code
						//Utils.log("method db");
					}
				}
				r.close();
			} catch (Exception e) {
				unlock();
				new IJError(e);
				if (null != i_stream) {
					try { i_stream.close(); } catch (Exception ie) { new IJError(ie); }
				}
				return NOT_FOUND;
			}

			// if it doesn't exist (because of some error), create it and save it
			try {
				if (null == image) {
					unlock(); // !!
					image = p.createImage(); // with adjusted channels
					//imp = fetchImagePlus(p); // what for?
					lock();
					awts.put(id, image);
					image = Snapshot.createSnap(p, image, Snapshot.SCALE); //image.getScaledInstance((int)(p.getWidth() * Snapshot.SCALE), (int)(p.getHeight() * Snapshot.SCALE), Snapshot.SCALE_METHOD);
					snaps.put(id, image);
					unlock(); // for the updateInDatabase method to lock on its own
					p.updateInDatabase("tiff_snapshot");
					//Utils.log("method recreate");
					return image;
				}
			} catch (Exception e) {
				unlock();
				return NOT_FOUND;
			}

			unlock();
			return image;
		}
	}

	/** Recursive. Place all Layer (but not LayerSet) objects with a key as Long(id). */
	private void unpackLayers(LayerThing root, Hashtable hs) {
		Object ob = root.getObject();
		if (ob instanceof Layer) hs.put(new Long(((DBObject)ob).getId()), ob);
		if (null == root.getChildren()) return;
		Iterator it = root.getChildren().iterator();
		while (it.hasNext()) {
			LayerThing child = (LayerThing)it.next();
			unpackLayers(child, hs);
		}
	}

	/* GENERIC, from DBObject calls */
	public boolean addToDatabase(DBObject ob) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return false;
			}
			try {
				Method method = getClass().getDeclaredMethod("addToDatabase", new Class[]{ob.getClass()});
				//Utils.log2("invoked method: " + method);
				method.invoke(this, new Object[]{ob});
			} catch (NoSuchMethodException nsme) {
				// try the interfaces (stupid java!)
				Class[] interfaces = ob.getClass().getInterfaces();
				for (int i=0; i<interfaces.length; i++) {
					try {
						Method method = getClass().getDeclaredMethod("addToDatabase", new Class[]{interfaces[i]});
						method.invoke(this, new Object[]{ob});
						// on success, end, to ensure only one invocation
						unlock();
						return true;
					} catch (Exception e) { // NoSuchMethodException and IllegalAccessException and InvocationTargetException
						Utils.log("Loader: Not for " + interfaces[i] + " : " + e);
						new IJError(e);
					}
				}
				Utils.log("Loader: no method for addToDatabase(" + ob.getClass().getName() + ")");
				unlock();
				return false;
			} catch (Exception e) {
				new IJError(e);
				if (e instanceof SQLException) { 
					Exception next = ((SQLException)e).getNextException();
					if (null != next) { new IJError(next); }
				}
				unlock();
				return false;
			}
			unlock();
			return true;
		}
	}

	public boolean updateInDatabase(DBObject ob, String key) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return false;
			}
			try {
				Method method = getClass().getDeclaredMethod("updateInDatabase", new Class[]{ob.getClass(), key.getClass()});
				method.invoke(this, new Object[]{ob, key});
			} catch (NoSuchMethodException nsme) {
				// try the interfaces (stupid java!)
				Class[] interfaces = ob.getClass().getInterfaces();
				for (int i=0; i<interfaces.length; i++) {
					try {
						Method method = getClass().getDeclaredMethod("updateInDatabase", new Class[]{interfaces[i], key.getClass()});
						method.invoke(this, new Object[]{ob, key});
						// on success, end, to ensure only one invocation
						unlock();
						return true;
					} catch (Exception e) { // NoSuchMethodException and IllegalAccessException
						Utils.debug("Loader: Not for " + interfaces[i]);
						new IJError(e);
					}
				}
				Utils.log("Loader: no method for updateInDatabase(" + ob.getClass().getName() + ")");
				unlock();
				return false;
			} catch (Exception e) {
				unlock();
				new IJError(e);
				if (e instanceof SQLException) { 
					Exception next = ((SQLException)e).getNextException();
					if (null != next) { new IJError(next); }
				}
				return false;
			}
			unlock();
			return true;
		}
	}

	public boolean removeFromDatabase(DBObject ob) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return false;
			}
			try {
				Method method = getClass().getDeclaredMethod("removeFromDatabase", new Class[]{ob.getClass()});
				method.invoke(this, new Object[]{ob});
			} catch (NoSuchMethodException nsme) {
				// try the interfaces (stupid java!)
				Class[] interfaces = ob.getClass().getInterfaces();
				for (int i=0; i<interfaces.length; i++) {
					try {
						Method method = getClass().getDeclaredMethod("removeFromDatabase", new Class[]{interfaces[i]});
						method.invoke(this, new Object[]{ob});
						// on success, end, to ensure only one invocation
						unlock();
						return true;
					} catch (Exception e) { // NoSuchMethodException and IllegalAccessException
						Utils.log("Loader: Not for " + interfaces[i]);
					}
				}
				Utils.log("Loader: no method for removeFromDatabase(" + ob.getClass().getName() + ")");
				unlock();
				return false;
			} catch (Exception e) {
				new IJError(e);
				if (e instanceof SQLException) { 
					Exception next = ((SQLException)e).getNextException();
					if (null != next) { new IJError(next); }
				}
				unlock();
				return false;
			}
			unlock();
			return true;
		}
	}

	/* Project methods ****************************************************************/

	private void addToDatabase(Project project) throws Exception {
		Utils.debug("Adding project to database.");
		connection.prepareStatement("INSERT INTO ab_projects (id, title, trakem2_version) VALUES (" + project.getId() + ",'" + project.toString() + "','" + Utils.version + "')").executeUpdate();
	}

	private void updateInDatabase(Project project, String key) throws Exception {
		StringBuffer sb_query = new StringBuffer("UPDATE ab_projects SET ");
		if (key.equals("title")) {
			sb_query.append(key).append("='").append(project.toString()).append("'");
		} else {
			Utils.log("Loader.updateInDatabase(Project, String): don't know what to do with key = " + key);
			return;
		}
		sb_query.append(" WHERE id=").append(project.getId());
		connection.prepareStatement(sb_query.toString()).executeUpdate();
	}

	private void removeFromDatabase(Project project)  throws Exception {
		boolean autocommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		Statement st = connection.createStatement();
		long project_id = project.getId();
		try {
		// delete the attributes
		st.addBatch("DELETE FROM ab_attributes WHERE thing_id IN (SELECT thing_id FROM ab_things WHERE ab_things.project_id=" + project_id + ")");
		// delete the things
		st.addBatch("DELETE FROM ab_things WHERE project_id=" + project_id);
		// delete the displays
		st.addBatch("DELETE FROM ab_displays WHERE layer_id IN (SELECT id as layer_id FROM ab_layers WHERE ab_layers.project_id=" + project_id + ")");
		// delete the links
		st.addBatch("DELETE FROM ab_links WHERE project_id=" + project_id);
		// delete the patches
		st.addBatch("DELETE FROM ab_patches WHERE id IN (SELECT ab_displayables.id as id FROM ab_displayables,ab_layers WHERE ab_displayables.layer_id=ab_layers.id AND ab_layers.project_id=" + project_id + ")");
		// delete the profiles
		st.addBatch("DELETE FROM ab_profiles WHERE id IN (SELECT ab_displayables.id as id FROM ab_displayables,ab_layers WHERE ab_displayables.layer_id=ab_layers.id AND ab_layers.project_id=" + project_id + ")");
		// delete the pipe points
		st.addBatch("DELETE FROM ab_pipe_points WHERE pipe_id IN (SELECT id as pipe_id FROM ab_zdisplayables WHERE ab_zdisplayables.project_id=" + project_id + ")"); 
		// delete the zdisplayables
		st.addBatch("DELETE FROM ab_zdisplayables WHERE id IN (SELECT ab_displayables.id as id FROM ab_displayables,ab_layers WHERE ab_displayables.layer_id=ab_layers.id AND ab_layers.project_id=" + project_id + ")");
		// delete the layer sets
		st.addBatch("DELETE FROM ab_displayables WHERE id IN (SELECT id FROM ab_layer_sets WHERE ab_layer_sets.project_id=" + project_id + ")");
		st.addBatch("DELETE FROM ab_layer_sets WHERE project_id=" + project_id);
		// delete the labels
		st.addBatch("DELETE FROM ab_labels WHERE id IN (SELECT ab_displayables.id as id FROM ab_displayables, ab_layers WHERE ab_displayables.layer_id=ab_layers.id AND ab_layers.project_id=" + project_id + ")");
		// delete the displayables
		st.addBatch("DELETE FROM ab_displayables WHERE layer_id IN (SELECT id as layer_id FROM ab_layers WHERE ab_layers.project_id=" + project_id + ")");
		// delete the layers
		st.addBatch("DELETE FROM ab_layers WHERE project_id=" + project_id);
		// delete the project
		st.addBatch("DELETE FROM ab_projects WHERE id=" + project_id);

		// not deleting/reseting the ab_ids sequence, for other projects depend on it!

		st.executeBatch();
		connection.commit();
		} catch (SQLException sqle) {
			Exception next = sqle.getNextException();
			if (null != next) new IJError(next);
			connection.setAutoCommit(autocommit);
			return;
		} catch (Exception e) {
			new IJError(e);
			connection.setAutoCommit(autocommit);
			return;
		}

		Utils.log("Project " + project_id + " successfully deleted.");

		// restore previous commit value:
		connection.setAutoCommit(autocommit);

	}

	/* Thing methods ****************************************************************/

	private void addToDatabase(Thing thing) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_things (id, project_id, type, parent_id, object_id) VALUES (")
				.append(((DBObject)thing).getId()).append(',')
				.append(((DBObject)thing).getProject().getId()).append(",'")
				.append(thing.getType()).append("',")
				.append(null == thing.getParent() ? -1L : ((DBObject)thing.getParent()).getId()).append(',')
				.append(thing.getObject() instanceof DBObject ? ((DBObject)thing.getObject()).getId() : -1L).append(')')
				.toString()).executeUpdate();
	}

	/** Shared by both LayerThing and ProjectThing, since both are saved in the same table ab_things */
	private void updateInDatabase(Thing thing, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_things SET ");
		if (key.equals("title")) {
			sb.append("title='").append(thing.getTitle()).append("'");
		} else if (key.equals("parent_id")) {
			sb.append("parent_id=").append(null == thing.getParent() ? -1L : ((DBObject)thing.getParent()).getId());
		/*} else if (key.equals("attributes")) {
			// TEMPORARY not implemented yet
			System.out.println("Loader.updateInDatabase: key attributes not yet implemented.");
			return;
		*/
		} else if (key.equals("type")) {
			sb.append("type='").append(thing.getType()).append("'");
		} else {
			Utils.log("Loader.updateInDatabase(Thing): don't know what to do with key: " + key);
			return;
		}
		sb.append(" WHERE id=").append(((DBObject)thing).getId());
		connection.prepareStatement(sb.toString()).executeUpdate();
	}

	private void removeFromDatabase(Thing thing) throws Exception {
		connection.prepareStatement("DELETE FROM ab_things WHERE id=" + ((DBObject)thing).getId()).execute();
	}

	/** ProjectThing methods */
	private void addToDatabase(ProjectThing pt) throws Exception {
		addToDatabase((Thing)pt);
	}
	private void updateInDatabase(ProjectThing pt, String key)  throws Exception {
		if (key.startsWith("expanded")) {
			StringBuffer sb = new StringBuffer("UPDATE ab_things SET ");
			if (-1 != key.indexOf('=')) {
				sb.append(key);
			} else {
				sb.append("expanded='").append(pt.getProject().getProjectTree().isExpanded(pt)).append('\'');
			}
			sb.append(" WHERE id=").append(pt.getId());
			connection.prepareStatement(sb.toString()).executeUpdate();
		} else {
			updateInDatabase((Thing)pt, key);
		}
	}
	private void removeFromDatabase(ProjectThing pt)  throws Exception {
		removeFromDatabase((Thing)pt);
	}

	/** TemplateThing methods */
	private void addToDatabase(TemplateThing tt) throws Exception {
		addToDatabase((Thing)tt);
		/*
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_things (id, project_id, type, title, parent_id, object_id) VALUES (")
				.append(tt.getId()).append(',')
				.append(tt.getProject().getId()).append(",'")
				.append(tt.getType()).append("','null',")
				.append(((null != tt.getParent()) ? ((TemplateThing)tt.getParent()).getId() : -1L)).append(",-1)")
				.toString()).executeUpdate();
		*/
	}
	private void updateInDatabase(TemplateThing tt, String key) throws Exception {
		StringBuffer sb = new StringBuffer();
		if (key.equals("type")) {
			sb.append("UPDATE ab_things SET type='").append(tt.getType()).append("' WHERE id=").append(tt.getId());
			Utils.log("Renaming type for tt.id=" + tt.getId() + " to type=" + tt.getType());
		} else if (key.startsWith("add_child")) {
			long child_id = Long.parseLong(key.substring(10));
		} else if (key.startsWith("remove_child")) {
			long child_id = Long.parseLong(key.substring(13));
		/*} else if (key.equals("attributes")) {
			// TEMPORARY not implemented yet
			System.out.println("Loader.updateInDatabase: key attributes not yet implemented.");
			return;
		*/
		} else if (key.startsWith("expanded")) {
			// ignore
		} else {
			Utils.log("Loader.updateInDatabase(TemplateThing): don't know what to do with key: " + key);
			return;
		}
		connection.prepareStatement(sb.toString()).executeUpdate();
	}
	private void removeFromDatabase(TemplateThing tt) throws Exception {
		removeFromDatabase((Thing)tt);
	}

	/* Displayable methods: accessed from each subclass **************************************/

	private void addToDatabase(Displayable displ) throws Exception {
		//connection.prepareStatement(new StringBuffer("INSERT INTO ab_displayables (id, title, x, y, width, height) VALUES (").append(displ.getId()).append(",'").append(displ.getTitle()).append("',").append(displ.getX()).append(',').append(displ.getY()).append(',').append(displ.getWidth()).append(',').append(displ.getHeight()).append(')').toString()).executeUpdate();
		stmt_add_displayable.setLong(1, displ.getId());
		stmt_add_displayable.setString(2, displ.getTitle());
		stmt_add_displayable.setDouble(3, displ.getX());
		stmt_add_displayable.setDouble(4, displ.getY());
		stmt_add_displayable.setDouble(5, displ.getWidth());
		stmt_add_displayable.setDouble(6, displ.getHeight());
		stmt_add_displayable.executeUpdate();
	}

	private void updateInDatabase(Displayable displ, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_displayables SET ");
		if (key.equals("position")) {
			//Utils.log2("updating position of " + displ + " to " + displ.getX() + "," + displ.getY());
			sb.append("x=").append(displ.getX())
			  .append(",y=").append(displ.getY());
		} else if (key.equals("dimensions")) {
			sb.append("width=").append(displ.getWidth())
			  .append(",height=").append(displ.getHeight());
		} else if (key.equals("position+dimensions")) {
			sb.append("x=").append(displ.getX())
			  .append(",y=").append(displ.getY())
			  .append(",width=").append(displ.getWidth())
			  .append(",height=").append(displ.getHeight());
		} else if (key.equals("position+rot")) {
			sb.append("x=").append(displ.getX())
			  .append(",y=").append(displ.getY())
			  .append(",rot=").append(displ.getRotation());
		} else if (key.equals("alpha")) {
			sb.append("alpha=").append(displ.getAlpha());
		} else if (key.equals("title")) {
			sb.append("title='").append(displ.getTitle()).append("'");
		} else if (key.equals("rot")) {
			sb.append("rot=").append(displ.getRotation());
		} else if (key.equals("color")) {
			Color color = displ.getColor();
			sb.append("color_red=").append(color.getRed())
			  .append(",color_green=").append(color.getGreen())
			  .append(",color_blue=").append(color.getBlue());
		} else if (key.equals("visible")) {
			sb.append("visible=").append(displ.isVisible());
		} else if (key.equals("layer_id")) {
			sb.append("layer_id=").append(displ.getLayer().getId());
		} else if (key.equals("all")) {
			sb.append("layer_id=").append( null == displ.getLayer() ? -1 : displ.getLayer().getId() )
			  .append(",title='").append(displ.getTitle()) // "'" appended below!
			  .append("',x=").append(displ.getX())
			  .append(",y=").append(displ.getY())
			  .append(",width=").append(displ.getWidth())
			  .append(",height=").append(displ.getHeight())
			  .append(",alpha=").append(displ.getAlpha())
			  .append(",visible=").append(displ.isVisible());
			Color color = displ.getColor();
			sb.append(",color_red=").append(color.getRed())
			  .append(",color_green=").append(color.getGreen())
			  .append(",color_blue=").append(color.getBlue())
			  .append(",rot=").append(displ.getRotation())
			  .append(",locked=").append(displ.isLocked2());
		} else if (key.equals("locked")) {
			sb.append("locked=").append(displ.isLocked());
		} else {
			Utils.log("Loader.updateInDatabase(Displayable): don't know what to do with key: " + key);
			return;
		}

		sb.append(" WHERE id=").append(displ.getId());
		connection.prepareStatement(sb.toString()).executeUpdate();
	}

	private void removeFromDatabase(Displayable displ) throws Exception {
		connection.prepareStatement("DELETE FROM ab_displayables WHERE id=" + displ.getId()).execute();
	}

	/* Patch methods ****************************************************************/

	/** Adds the given Patch and its ImagePlus as a new row, filling in the 'tiff_original' column with the zipped ImagePlus. */
	private void addToDatabase(Patch patch) throws Exception {
		InputStream i_stream = null;
		try {
			ImagePlus imp = imps.get(patch.getId());
			//PreparedStatement st = connection.prepareStatement(new StringBuffer("INSERT INTO ab_patches (id, imp_type, tiff_original) VALUES (").append(patch.getId()).append(',').append(imp.getType()).append(",?)").toString());
			stmt_add_patch.setLong(1, patch.getId());
			stmt_add_patch.setInt(2, imp.getType());
			i_stream = createZippedStream(imp);
			if (null == i_stream) {
				Utils.log("Loader.addToDatabase(Patch): null stream.");
				return;
			}
			stmt_add_patch.setBinaryStream(3, i_stream, i_stream.available());
			stmt_add_patch.setDouble(4, patch.getMin());
			stmt_add_patch.setDouble(5, patch.getMax());
			stmt_add_patch.executeUpdate();
			i_stream.close();
		} catch (Exception e) {
			if (null != i_stream) try { i_stream.close(); } catch (Exception ie) { new IJError(ie); }
			Utils.showMessage("Could not add Patch image.");
			new IJError(e);
			return;
		}
		//finally:
		addToDatabase((Displayable)patch);
	}

	/** The ImagePlus, if updated, is saved in the 'tiff_working' column always. */
	private void updateInDatabase(Patch patch, String key) throws Exception {

		if (key.equals("tiff_snapshot")) {
			InputStream i_stream = null;
			try {
				ImagePlus imp = new ImagePlus("s", snaps.get(patch.getId())); // not calling fetchSnapshot because old code could end in a loop.
				if (null == imp) {
					Utils.log2("DBLoader: snapshot ImagePlus is null!");
					stmt_update_snap.setNull(1, java.sql.Types.BINARY);
				} else {
					i_stream = createZippedStream(imp);
					stmt_update_snap.setBinaryStream(1, i_stream, i_stream.available());
					imp.flush();
				}
				stmt_update_snap.setLong(2, patch.getId());
				stmt_update_snap.executeUpdate();
			} catch (Exception e) {
				new IJError(e);
			} finally {
				if (null != i_stream) try { i_stream.close(); } catch (Exception e1) { new IJError(e1); }
			}
			return;
		}

		StringBuffer sb = new StringBuffer("UPDATE ab_patches SET ");
		boolean update_imp = false;

		if (key.equals("tiff_working")) {
			sb.append("imp_type=").append(patch.getType())
			  .append(", tiff_working=?");
			update_imp = true;
		} else if (key.equals("remove_tiff_working")) {
			sb.append("tiff_working=NULL");
		} else if (key.equals("min_and_max")) {
			sb.append("min=").append(patch.getMin())
			  .append(", max=").append(patch.getMax());
		} else {
			// try the Displayable level
			updateInDatabase((Displayable)patch, key);
			return;
		}

		PreparedStatement st = connection.prepareStatement(sb.append(" WHERE id=").append(patch.getId()).toString());
		int i = 1;
		InputStream i_stream2 = null;
		try {
			if (update_imp) {
				ImagePlus imp = imps.get(patch.getId()); // WARNING if the cache is very small relative to the size of the images, this strategy may fail
				i_stream2 = createZippedStream(imp);
				st.setBinaryStream(i, i_stream2, i_stream2.available());
				i++; // defensive programming: if later I add any other ..
			}

			st.executeUpdate();

			if (null != i_stream2) i_stream2.close();

		} catch (Exception e) {
			new IJError(e);
			if (null != i_stream2) try { i_stream2.close(); } catch (Exception e2) { new IJError(e2); }
		}
	}

	private void removeFromDatabase(Patch patch) throws Exception {
		connection.prepareStatement("DELETE FROM ab_patches WHERE id=" + patch.getId()).execute();
		//finally:
		removeFromDatabase((Displayable)patch); // problem: this is not atomic.

		// finally, remove the images from the cache if any
		Image snap = snaps.remove(patch.getId());
		if (null != snap) snap.flush();
		Image awt = awts.remove(patch.getId());
		if (null != awt) awt.flush();
		ImagePlus imp = imps.remove(patch.getId());
		if (null != imp) imp.flush(); // calls System.gc()
	}

	/*  Attribute methods ****************************************************************/

	private void addToDatabase(Attribute attr) throws Exception {
		Object owner = attr.getOwner();
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_attributes (id, thing_id, name, value) VALUES (").append(((DBObject)attr).getId()).append(',').append(owner == null ? -1 : ((DBObject)owner).getId()).append(",'").append(attr.getTitle()).append("','").append(attr.getObjectString()).append("')").toString()).executeUpdate();
	}

	private void updateInDatabase(Attribute attr, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_attributes SET ");
		if (key.equals("object")) {
			sb.append("value='").append(null == attr.getObject() ? "" : attr.getObjectString()).append("'");
		} else {
			Utils.log("Loader.updateInDatabase(Attribute): don't know what to do with key: " + key);
			return;
		}
		sb.append(" WHERE id=").append(((DBObject)attr).getId());
		connection.prepareStatement(sb.toString()).executeUpdate();
	}

	private void removeFromDatabase(Attribute attr) throws Exception {
		connection.prepareStatement("DELETE FROM ab_attributes WHERE id=" + ((DBObject)attr).getId()).execute();
	}
	
	/*  ProjectAttribute methods *********************/

	private void addToDatabase(ProjectAttribute attr) throws Exception {
		addToDatabase((Attribute)attr);
	}
	private void updateInDatabase(ProjectAttribute attr, String key) throws Exception {
			updateInDatabase((Attribute)attr, key);
	}
	private void removeFromDatabase(ProjectAttribute attr) throws Exception {
		removeFromDatabase((Attribute)attr);
	}

	/*  TemplateAttribute methods *********************/

	private void addToDatabase(TemplateAttribute attr) throws Exception {
		addToDatabase((Attribute)attr);
	}
	private void updateInDatabase(TemplateAttribute attr, String key) throws Exception {
			updateInDatabase((Attribute)attr, key);
	}
	private void removeFromDatabase(TemplateAttribute attr) throws Exception {
		removeFromDatabase((Attribute)attr);
	}

	/* Layer methods ****************************************************************/

	private void addToDatabase(Layer layer) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_layers (id, project_id, layer_set_id, z, thickness) VALUES (").append(layer.getId()).append(',').append(layer.getProject().getId()).append(',').append(layer.getParent().getId()).append(',').append(layer.getZ()).append(',').append(layer.getThickness()).append(')').toString()).executeUpdate();
	}

	private void updateInDatabase(Layer layer, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_layers SET ");
		if (key.equals("stack_index")) {
			boolean autocommit = connection.getAutoCommit();
			try {
				Iterator it = layer.getDisplayables().iterator();
				connection.setAutoCommit(false);
				Statement st = connection.createStatement(); // this is the kind of place to use preparedStatement properly, by filling in the 'i' and the 'id' only
				int i = 0;
				while (it.hasNext()) {
					DBObject dbo = (DBObject)it.next();
					st.addBatch("UPDATE ab_displayables SET stack_index=" + i + " WHERE id=" + dbo.getId());
					i++;
				}
				it = layer.getParent().getZDisplayables().iterator();
				while (it.hasNext()) {
					DBObject dbo = (DBObject)it.next();
					st.addBatch("UPDATE ab_displayables SET stack_index=" + i + " WHERE id=" + dbo.getId());
					i++;
				}
				st.executeBatch();
				connection.commit();
				// restore
				connection.setAutoCommit(autocommit);
			} catch (SQLException sqle) {
				new IJError(sqle);
				Exception next;
				if (null != (next = sqle.getNextException())) {
					new IJError(next);
				}
				try {
					connection.rollback();
					connection.setAutoCommit(autocommit);
				} catch (SQLException sqle2) {
					new IJError(sqle2);
				}
			}
			return;
			//
		} else if (key.equals("z")) {
			sb.append("z=").append(layer.getZ());
		} else if (key.equals("thickness")) {
			sb.append("thickness=").append(layer.getThickness());
		} else if (key.equals("layer_set_id")) {
			sb.append("layer_set_id=").append(layer.getParent().getId());
		} else {
			Utils.log("Loader.updateInDatabase(Layer): don't know what to do with key: " + key);
			return;
		}
		sb.append(" WHERE id=").append(layer.getId());
		connection.prepareStatement(sb.toString()).executeUpdate();
	}

	private void removeFromDatabase(Layer layer) throws Exception {
		connection.prepareStatement("DELETE FROM ab_layers WHERE id=" + layer.getId()).execute();
	}

	/*  LayerSet methods ****************************************************************/

	private void addToDatabase(LayerSet layer_set) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_layer_sets (id, project_id, parent_layer_id, active_layer_id, layer_width, layer_height, rot_x, rot_y, rot_z) VALUES (").append(layer_set.getId()).append(',').append(layer_set.getProject().getId()).append(',').append(null == layer_set.getParent() ? -1 : layer_set.getParent().getId()).append(',').append(null == layer_set.getActiveLayer() ? -1 : layer_set.getActiveLayer().getId()).append(',').append(layer_set.getLayerWidth()).append(',').append(layer_set.getLayerHeight()).append(',').append(layer_set.getRotX()).append(',').append(layer_set.getRotY()).append(',').append(layer_set.getRotZ()).append(')').toString()).executeUpdate();
		// also:
		addToDatabase((Displayable)layer_set);
	}

	private void updateInDatabase(LayerSet layer_set, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_layer_sets SET ");
		if (key.equals("active_layer_id")) {
			sb.append("active_layer_id=").append(layer_set.getActiveLayer().getId());
		} else if (key.equals("parent_id")) {
			sb.append("parent_id=").append(layer_set.getParent().getId());
		} else if (key.startsWith("rot")) {
			sb.append("rot_x=").append(layer_set.getRotX()).append(", rot_y=").append(layer_set.getRotY()).append(", rot_z=").append(layer_set.getRotZ());
		} else if (key.equals("layer_dimensions")) {
			sb.append("layer_width=").append(layer_set.getLayerWidth()).append(",layer_height=").append(layer_set.getLayerHeight());
		} else if (key.equals("snapshots_enabled")) {
			sb.append("snapshots_enabled=").append(layer_set.areSnapshotsEnabled());
		} else {
			// try the Displayable level
			updateInDatabase((Displayable)layer_set, key);
			return;
		}
		sb.append(" WHERE id=").append(layer_set.getId());
		connection.prepareStatement(sb.toString()).executeUpdate();
	}

	private void removeFromDatabase(LayerSet layer_set) throws Exception {
		// remove the layer set only, the layers are removed on their own
		connection.prepareStatement("DELETE FROM ab_layer_sets WHERE id=" + layer_set.getId()).execute();
		// finally:
		removeFromDatabase((Displayable)layer_set);
	}

	/* Profile methods ****************************************************************/

	private void addToDatabase(Profile profile) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_profiles (id) VALUES (").append(profile.getId()).append(')').toString()).executeUpdate();
		addToDatabase((Displayable)profile);
	}

	private void updateInDatabase(Profile profile, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_profiles SET ");
		boolean update_points = false;
		if (key.equals("points")) {
			sb.append("polygon=?");
			update_points = true;
		} else if (key.equals("closed")) {
			sb.append("closed=").append(profile.isClosed());
		} else if (key.equals("all")) {
			sb.append("closed=").append(profile.isClosed())
			  .append(",polygon=?");
			updateInDatabase((Displayable)profile, key);
			update_points = true;
		} else {
			// try the Displayable level
			updateInDatabase((Displayable)profile, key);
			return;
		}
		sb.append(" WHERE id=").append(profile.getId());

		PreparedStatement statement = connection.prepareStatement(sb.toString());
		if (update_points) {
			unlock();
			statement.setObject(1, makePGpolygon(profile.getBezierArrays()));
			lock();
		}
		statement.executeUpdate();
	}

	private PGpolygon makePGpolygon(final double[][][] bezarr) {
		final PGpoint[] points = new PGpoint[bezarr[0][0].length * 3];
		final double[][] p_l = bezarr[0];
		final double[][] p = bezarr[1];
		final double[][] p_r = bezarr[2];
		for (int i=0, j=0; i<points.length; i+=3, j++) {
			points[i] = new PGpoint(p_l[0][j], p_l[1][j]);
			points[i+1] = new PGpoint(p[0][j], p[1][j]);
			points[i+2] = new PGpoint(p_r[0][j], p_r[1][j]);
		}
		return new PGpolygon(points);
	}

	private Polygon makePolygon(PGpolygon pg) {
		final Polygon pol = new Polygon();
		for (int i=0; i<pg.points.length; i++) {
			pol.addPoint((int)pg.points[i].x, (int)pg.points[i].y);
		}
		return pol;
	}

	private void removeFromDatabase(Profile profile) throws Exception {
		connection.prepareStatement("DELETE FROM ab_profiles WHERE id=" + profile.getId()).execute();
		// finally:
		removeFromDatabase((Displayable)profile);
	}

	/* Display methods ****************************************************************/

	private void addToDatabase(Display display) throws Exception {
		StringBuffer sb = new StringBuffer("INSERT INTO ab_displays (id, layer_id, window_x, window_y, magnification, srcrect_x, srcrect_y, srcrect_width, srcrect_height) VALUES (");
		sb.append(display.getId()).append(',');
		sb.append(display.getLayer().getId()).append(',');
		Rectangle r = display.getBounds();
		sb.append(r.x).append(',')
		  .append(r.y).append(',')
		  .append(display.getCanvas().getMagnification()).append(',')
		;
		r = display.getCanvas().getSrcRect();
		sb.append(r.x).append(',')
		  .append(r.y).append(',')
		  .append(r.width).append(',')
		  .append(r.height)
		;
		connection.prepareStatement(sb.append(')').toString()).executeUpdate();
	}

	private void updateInDatabase(Display display, String key) throws Exception {
		StringBuffer sb = new StringBuffer("UPDATE ab_displays SET ");
		if (key.equals("active_displayable_id")) {
			sb.append(key).append("=").append(null == display.getActive() ? -1L : display.getActive().getId());
		} else if (key.equals("position")) {
			Rectangle r = display.getBounds();
			sb.append("window_x=").append(r.x)
			  .append(",window_y=").append(r.y);
		} else if (key.equals("srcRect")) {
			Rectangle r = display.getCanvas().getSrcRect();
			sb.append("magnification=").append(display.getCanvas().getMagnification())
			  .append(",srcrect_x=").append(r.x)
			  .append(",srcrect_y=").append(r.y)
			  .append(",srcrect_width=").append(r.width)
			  .append(",srcrect_height=").append(r.height);
		} else if (key.equals("layer_id")) {
			sb.append("layer_id=").append(display.getLayer().getId());
		} else if (key.equals("c_alphas")) {
			sb.append("c_alphas=").append(display.getChannelAlphas());
			sb.append(", c_alphas_state=").append(display.getChannelAlphasState());
		} else if (key.equals("scroll_step")) {
			sb.append("scroll_step=").append(display.getScrollStep());
		} else {
			Utils.log("Loader.updateInDatabase(Display): don't know what to do with key: " + key);
			return;
		}
		connection.prepareStatement(sb.append(" WHERE id=").append(display.getId()).toString()).executeUpdate();
	}

	private void removeFromDatabase(Display display) throws Exception {
		connection.prepareStatement("DELETE FROM ab_displays WHERE id=" + display.getId()).execute();
	}
	
	/* Ball methods ****************************************************************/
	private void addToDatabase(Ball ball) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_zdisplayables (id, project_id) VALUES (").append(ball.getId()).append(',').append(ball.getProject().getId()).append(')').toString()).executeUpdate();
		//finally:
		addToDatabase((Displayable)ball);
	}
	private void updateInDatabase(Ball ball, String key) throws Exception {
		if (!connectToDatabase()) {
			Utils.log("Not connected and can't connect to database.");
			return;
		}
		try {
			boolean autocommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			Statement st = connection.createStatement();

			StringBuffer sb_query = new StringBuffer("UPDATE ab_zdisplayables SET ");
			boolean update_all_points = false;

			if (key.startsWith("INSERT INTO ab_ball_points ")) {
				connection.prepareStatement(key).executeUpdate();
				connection.commit();
				// restore
				connection.setAutoCommit(autocommit);
				return;
			} else if (key.equals("points")) {
				update_all_points = true;
			} else if (key.startsWith("UPDATE ab_ball_points")) {
				// used to update points individually
				connection.prepareStatement(key).executeUpdate();
				connection.commit();
				// restore
				connection.setAutoCommit(autocommit);
				return;
			} else if (key.equals("layer_set_id")) {
				sb_query.append("layer_set_id=").append(ball.getLayerSet().getId());
			} else {
				// Displayable level
				connection.setAutoCommit(autocommit);
				updateInDatabase((Displayable)ball, key);
				return;
			}

			if (sb_query.length() > 28) { // 28 is the length of the string 'UPDATE ab_zdisplayables SET '
				st.addBatch(sb_query.toString());
			}

			if (update_all_points) {
				// delete and re-add
				st.addBatch("DELETE FROM ab_ball_points WHERE ball_id=" + ball.getId());
				String[] s_points = ball.getPointsForSQL();
				for (int i=0; i<s_points.length; i++) {
					st.addBatch(s_points[i]);
				}
			}

			sb_query.append(" WHERE pipe_id=").append(ball.getId());

			// commit
			st.executeBatch();
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);

		} catch (SQLException sqle) {
			new IJError(sqle);
			Exception next;
			if (null != (next = sqle.getNextException())) {
				new IJError(next);
			}
			try {
				connection.rollback();
				connection.setAutoCommit(true); // default ..
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	private void removeFromDatabase(Ball ball) throws Exception {
		boolean autocommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			Statement st = connection.createStatement();
			// remove points
			st.addBatch("DELETE FROM ab_ball_points WHERE ball_id=" + ball.getId());
			// remove the ball itself
			st.addBatch("DELETE FROM ab_zdisplayables WHERE id=" + ball.getId());
			// remove associated Displayable
			st.addBatch("DELETE FROM ab_displayables WHERE id=" + ball.getId());
			st.executeBatch();
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);
		} catch (SQLException sqle) {
			new IJError(sqle);
			try {
				connection.rollback();
				connection.setAutoCommit(autocommit);
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	/* Pipe methods ****************************************************************/

	private void addToDatabase(Pipe pipe) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_zdisplayables (id, project_id) VALUES (").append(pipe.getId()).append(',').append(pipe.getProject().getId()).append(')').toString()).executeUpdate();
		//finally:
		addToDatabase((Displayable)pipe);
	}

	private void updateInDatabase(Pipe pipe, String key) throws Exception {
		if (!connectToDatabase()) {
			Utils.log("Not connected and can't connect to database.");
			return;
		}
		try {
			boolean autocommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			Statement st = connection.createStatement();

			StringBuffer sb_query = new StringBuffer("UPDATE ab_zdisplayables SET ");
			boolean update_all_points = false;

			if (key.equals("points")) {
				update_all_points = true;
			} else if (key.startsWith("UPDATE ab_pipe_points")) {
				// used to update points individually
				connection.prepareStatement(key).executeUpdate();
				connection.commit();
				// restore
				connection.setAutoCommit(autocommit);
				return;
			} else if (key.equals("layer_set_id")) {
				sb_query.append("layer_set_id=").append(pipe.getLayerSet().getId());
			} else {
				// Displayable level
				connection.setAutoCommit(autocommit);
				updateInDatabase((Displayable)pipe, key);
				return;
			}

			if (sb_query.length() > 28) { // 28 is the length of the string 'UPDATE ab_zdisplayables SET '
				st.addBatch(sb_query.toString());
			}

			if (update_all_points) {
				// delete and re-add
				st.addBatch("DELETE FROM ab_pipe_points WHERE pipe_id=" + pipe.getId());
				String[] s_points = pipe.getPointsForSQL();
				for (int i=0; i<s_points.length; i++) {
					st.addBatch(s_points[i]);
				}
			}

			sb_query.append(" WHERE pipe_id=").append(pipe.getId());

			// commit
			st.executeBatch();
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);

		} catch (SQLException sqle) {
			new IJError(sqle);
			Exception next;
			if (null != (next = sqle.getNextException())) {
				new IJError(next);
			}
			try {
				connection.rollback();
				connection.setAutoCommit(true); // default ..
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	private void removeFromDatabase(Pipe pipe) throws Exception {
		boolean autocommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			Statement st = connection.createStatement();
			// remove points
			st.addBatch("DELETE FROM ab_pipe_points WHERE pipe_id=" + pipe.getId());
			// remove the pipe itself
			st.addBatch("DELETE FROM ab_zdisplayables WHERE id=" + pipe.getId());
			// remove associated Displayable
			st.addBatch("DELETE FROM ab_displayables WHERE id=" + pipe.getId());
			st.executeBatch();
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);
		} catch (SQLException sqle) {
			new IJError(sqle);
			try {
				connection.rollback();
				connection.setAutoCommit(autocommit);
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	/* DLabel  methods ****************************************************************/

	private void addToDatabase(DLabel label) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_labels (id, type, font_name, font_style, font_size) VALUES (").append(label.getId()).append(',').append(label.getType()).append(",'").append(label.getFont().getName()).append("',").append(label.getFont().getStyle()).append(',').append(label.getFont().getSize()).append(')').toString()).executeUpdate();
		// also:
		addToDatabase((Displayable)label);
	}

	private void updateInDatabase(DLabel label, String key) throws Exception {
		if (key.equals("font")) {
			connection.createStatement().executeQuery(new StringBuffer("UPDATE ab_labels SET font_name='").append(label.getFont().getName()).append("', font_style=").append(label.getFont().getStyle()).append(", font_size=").append(label.getFont().getSize()).append(" WHERE id=").append(label.getId()).toString());
		} else {
			updateInDatabase((Displayable)label, key);
		}
	}

	private void removeFromDatabase(DLabel label) throws Exception {
		connection.prepareStatement("DELETE FROM ab_labels WHERe id=" + label.getId()).execute();
		removeFromDatabase((Displayable)label);
	}

	/*  AreaList methods ****************************************************************/

	private void addToDatabase(AreaList arealist) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_zdisplayables (id, project_id) VALUES (").append(arealist.getId()).append(',').append(arealist.getProject().getId()).append(')').toString()).executeUpdate();
		//also:
		addToDatabase((Displayable)arealist);
	}

	private void updateInDatabase(AreaList arealist, String key) throws Exception {
		if (!connectToDatabase()) {
			Utils.log("Not connected and can't connect to database.");
			return;
		}
		try {
			boolean autocommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			StringBuffer sb_query = new StringBuffer("UPDATE ");

			if (key.equals("layer_set_id")) {
				Statement st = connection.createStatement();
				st.executeUpdate(sb_query.append(" ab_zdisplayables SET layer_set_id=").append(arealist.getLayerSet().getId()).append(" WHERE id=").append(arealist.getId()).toString());

			} else if (key.startsWith("points=")) {
				// update only
				long layer_id = Long.parseLong(key.substring(7));
				// remove exisiting paths for this layer_id
				connection.createStatement().executeUpdate(new StringBuffer("DELETE FROM ab_area_paths WHERE area_list_id=").append(arealist.getId()).append(" AND layer_id=").append(layer_id).toString());
				// add new paths
				unlock();
				ArrayList al_paths = arealist.getPaths(layer_id);
				lock();
				for (Iterator it = al_paths.iterator(); it.hasNext(); ) {
					PreparedStatement ps = connection.prepareStatement(new StringBuffer("INSERT INTO ab_area_paths (area_list_id, layer_id, polygon) VALUES (").append(arealist.getId()).append(',').append(layer_id).append(",?)").toString());
					ps.setObject(1, makePGpolygon((ArrayList)it.next()));
					ps.executeUpdate();
				}
			} else if (key.equals("all_points")) {
				// remove exisiting paths for this area_list_id
				connection.createStatement().executeUpdate(new StringBuffer("DELETE FROM ab_area_paths WHERE area_list_id=").append(arealist.getId()).toString());
				// add then new
				Hashtable ht = arealist.getAllPaths();
				for (Iterator eit = ht.entrySet().iterator(); eit.hasNext(); ) {
					Map.Entry entry = (Map.Entry)eit.next();
					long layer_id = ((Long)entry.getKey()).longValue();
					ArrayList al_paths = (ArrayList)entry.getValue();
					for (Iterator it = al_paths.iterator(); it.hasNext(); ) {
						PreparedStatement ps = connection.prepareStatement(new StringBuffer("INSERT INTO ab_area_paths (area_list_id, layer_id, polygon) VALUES (").append(arealist.getId()).append(',').append(layer_id).append(",?)").toString());
						ps.setObject(1, makePGpolygon((ArrayList)it.next()));
						ps.executeUpdate();
					}
				}
			} else if (key.equals("fill_paint")) {
				connection.createStatement().executeUpdate(new StringBuffer("UPDATE ab_area_paths SET fill_paint=").append(arealist.getFillPaint()).append(" WHERE area_list_id=").append(arealist.getId()).toString()); // overkill, but otherwise I need to remake the ZDisplayable tables (which I will do at some point)
			}
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);
		} catch (SQLException sqle) {
			new IJError(sqle);
			Exception next;
			if (null != (next = sqle.getNextException())) {
				new IJError(next);
			}
			try {
				connection.rollback();
				connection.setAutoCommit(true); // default ..
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	private void removeFromDatabase(AreaList arealist) throws Exception {
		if (!connectToDatabase()) {
			Utils.log("Not connected and can't connect to database.");
			return;
		}
		try {
			boolean autocommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			Statement st = connection.createStatement();
			st.addBatch("DELETE FROM ab_zdisplayables WHERE id=" + arealist.getId());
			st.addBatch("DELETE FROM ab_area_paths WHERE area_list_id=" + arealist.getId());
			st.executeBatch();
			connection.commit();
			// restore
			connection.setAutoCommit(autocommit);
		} catch (SQLException sqle) {
			new IJError(sqle);
			Exception next;
			if (null != (next = sqle.getNextException())) {
				new IJError(next);
			}
			try {
				connection.rollback();
				connection.setAutoCommit(true); // default ..
			} catch (SQLException sqle2) {
				new IJError(sqle2);
			}
		}
	}

	private PGpolygon makePGpolygon(final ArrayList al_points) {
		final PGpoint[] pol = new PGpoint[al_points.size()];
		for (int i=0; i<pol.length; i++) {
			Point p = (Point)al_points.get(i);
			pol[i] = new PGpoint(p.x, p.y);
		}
		return new PGpolygon(pol);
	}

	/*  methods ****************************************************************/

	/*
	private void addToDatabase(Project project) throws Exception {
		connection.prepareStatement(new StringBuffer("INSERT INTO ab_layer_sets () VALUES (").append().append().toString()).executeUpdate();

	}

	private void updateInDatabase(Project project, String key) throws Exception {

	}

	private void removeFromDatabase(Project project) throws Exception {

	}
	*/

	synchronized public void addCrossLink(long project_id, long id1, long id2) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return;
		}
		try {
			connection.prepareStatement(new StringBuffer("INSERT INTO ab_links (project_id, id1, id2) VALUES (").append(project_id).append(',').append(id1).append(',').append(id2).append(')').toString()).executeUpdate();
		} catch (Exception e) {
			new IJError(e);
			return;
		}
	}

	/** Remove a link between two objects. */
	synchronized public boolean removeCrossLink(long id1, long id2) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return false;
		}
		try {
			connection.prepareStatement(new StringBuffer("DELETE FROM ab_links WHERE (id1=").append(id1).append(" AND id2=").append(id2).append(") OR (id1=").append(id2).append(" AND id2=").append(id1).append(')').toString()).executeUpdate();
		} catch (Exception e) {
			new IJError(e);
			return false;
		}
		return true;
	}

	public ImagePlus fetchImagePlus(Patch p) {
		return fetchImagePlus(p, true);
	}

	/** Create snap is ignored here. */
	public ImagePlus fetchImagePlus(Patch p, boolean create_snap) {
		synchronized (db_lock) {
			lock();
			long id = p.getId();
			// see if the ImagePlus is cached:
			ImagePlus imp = imps.get(id);
			if (null != imp) {
				if (null != imp.getProcessor() && null != imp.getProcessor().getPixels()) { // may have been flushed by ImageJ, for example when making images from a stack
					unlock();
					return imp;
				} else {
					imp.flush(); // can't hurt
				}
			}
			// else, reload from database

			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}

			releaseMemory();

			InputStream i_stream = null;
			try {
				//StopWatch sw = new StopWatch();

				ResultSet r = connection.prepareStatement("SELECT tiff_working FROM ab_patches WHERE tiff_working IS NOT NULL AND id=" + id).executeQuery();
				boolean found = false;
				if (r.next()) {
					found = true;
					//sw.elapsed();
					i_stream = r.getBinaryStream("tiff_working");
					//sw.elapsed();
					imp = unzipTiff(i_stream, p.getTitle());
					//sw.elapsed();
					i_stream.close();
					imps.put(id, imp);
				}
				r.close();
				// if the working is not there, fetch the original instead
				if (!found) {
					r = connection.prepareStatement("SELECT tiff_original FROM ab_patches WHERE id=" + id).executeQuery();
					if (r.next()) {
						//sw.elapsed();
						i_stream = r.getBinaryStream("tiff_original");
						//sw.elapsed();
						imp = unzipTiff(i_stream, p.getTitle()); // will apply the preprocessor plugin to it as well
						//sw.elapsed();
						i_stream.close();
						imps.put(id, imp);
					}
					r.close();
				}
				// non-destructive contrast: min and max
				if (null != imp) {
					p.putMinAndMax(imp);
				}
			} catch (Exception e) {
				unlock();
				new IJError(e);
				if (null != i_stream) {
					try { i_stream.close(); } catch (Exception ie) { new IJError(ie); }
				}
				return null;
			}
			unlock();
			return imp;
		}
	}

	public Object[] fetchLabel(DLabel label) {
		synchronized (db_lock) {
			lock();
			//connect if disconnected
			if (!connectToDatabase()) {
				unlock();
				return null;
			}
			Object[] ob = null;
			try {
				ResultSet r = connection.prepareStatement("SELECT ab_labels.id, ab_displayables.id, title, ab_labels.type, font_name, font_style, font_size FROM ab_labels,ab_displayables WHERE ab_labels.id=ab_displayables.id AND id=" + label.getId()).executeQuery();
				if (r.next()) {
					ob = new Object[5];
					ob[0] = r.getString("title");
					ob[1] = r.getString("font_name");
					ob[2] = new Integer(r.getInt("font_style"));
					ob[3] = new Integer(r.getInt("font_size"));
					ob[4] = new Integer(r.getInt("type"));
				}
				r.close();
			} catch (Exception e) {
				unlock();
				new IJError(e);
				return null;
			}
			unlock();
			return ob;
		}
	}

	synchronized public ImagePlus fetchOriginal(Patch patch) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return null;
		}
		long imp_size = (long)(patch.getWidth() * patch.getHeight() * 4); // assume RGB, thus multiply by 4 (an int has 4 bytes)
		releaseMemory(0.5D, true, MIN_FREE_BYTES > imp_size ? MIN_FREE_BYTES : imp_size);
		ImagePlus imp = null;
		InputStream i_stream = null;
		try {
			ResultSet r = connection.prepareStatement("SELECT id, tiff_original FROM ab_patches WHERE id=" + patch.getId()).executeQuery();
			if (r.next()) {
				// fetch stream
				i_stream = r.getBinaryStream("tiff_original");
				imp = unzipTiff(i_stream, patch.getTitle());
				i_stream.close();
			}
			r.close();
		} catch (Exception e) {
			Utils.log("Loader.fetchOriginal: ERROR fetching original ImagePlus for Patch id=" + patch.getId());
			new IJError(e);
			if (null != i_stream) {
				try { i_stream.close(); } catch (Exception ee) { Utils.log("Loader.fetchOriginal: could not close stream."); }
			}
			return null;
		}
		if (null == imp) Utils.log("WARNING fetching a null original");
		return imp;
	}

	public void prepare(Layer layer) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return;
		}
		super.prepare(layer);
	}

	/** Preload Patch snapshots on a separate Thread, only if they intersect the given rectangle, in chunks of maximum n_bytes. If there is not enough memory, it will silently fail. */
	/* // NOT NEEDED if the database has proper settings for work_mem such as 128MB in a 4GB RAM system.
	public void preloadSnapshots(final Layer layer, final double magnification, final Rectangle srcRect, final Rectangle clipRect, final int n_bytes) {
		new Thread() {
			public void run() {
				Utils.log2("started preloader for " + layer);
				ArrayList al = layer.getDisplayables();
				Displayable[] d = new Displayable[al.size()];
				al.toArray(d);
				long size = 0;
				final ArrayList al_p = new ArrayList();
				for (int i=0; i<d.length; i++) { // the call to snaps.getId is not synchronized!
					if (null != snaps.get(d[i].getId()) || d[i].isOutOfRepaintingClip(magnification, srcRect, clipRect) || !(d[i] instanceof Patch)) continue;
					Patch p = (Patch)d[i];
					int snap_size = (int)(Snapshot.SCALE * p.getWidth() * p.getHeight() * (ImagePlus.GRAY8 == p.getType() ? 1 : 4));
					if (size + snap_size > n_bytes) {
						preloadSnapshots(al_p, size);
						size = 0;
						al_p.clear();
					} else {
						size += snap_size;
						al_p.add(p);
					}
				}
				if (0 != al_p.size()) {
					preloadSnapshots(al_p, size);
				}
				Utils.log2("finished preloader for " + layer);
			}
		}.start();
	}*/

	/** Preload the snapshots for the given Patch list. If there is not enough memory it will silently fail, since this is an accessory method. Returns if only one snapshot is to be preloaded. */
	/*
	private void preloadSnapshots(final ArrayList al_p, final long size) {
		ResultSet r = null;
		try {
			final int length = al_p.size();
			if (1 == length) return;
			final StringBuffer sb = new StringBuffer("SELECT id,tiff_snapshot from ab_patches WHERE id=");
			sb.append(((DBObject)al_p.get(length-1)).getId());
			for (int i=length-2; i>-1; i--) {
				DBObject dbo = (DBObject)al_p.get(i);
				sb.append(" OR id=").append(dbo.getId());
			}
			final Statement st = getPreloaderConnection().createStatement();
			//st.setFetchSize(1); // defeats the purpose of a quick load
			r = st.executeQuery(sb.toString());
			final Hashtable ht = new Hashtable();
			while (r.next()) {
				final InputStream i_stream = r.getBinaryStream(2);
				long id = r.getLong(1);
				if (null != snaps.get(id)) continue;
				if (null == i_stream) continue;
				final ImagePlus imp = unzipTiff(i_stream, "s");
				final Image snap = imp.getProcessor().createImage();
				imp.flush();
				ht.put(new Long(id), snap);
			}
			// finally, synchronize
			synchronized (db_lock) {
				lock();
				try {
					for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
						Map.Entry entry = (Map.Entry)it.next();
						snaps.put(((Long)entry.getKey()).longValue(), (Image)entry.getValue());
					}
				} catch (Exception e) {
					new IJError(e);
				} finally {
					unlock();
				}
			}
		} catch (Exception e) {
			Utils.log2("DBLoader.preload: " + e);
		} finally {
			try { if (null != r) r.close(); } catch(Exception ee) {}
		}
	}
	*/

	/** Rotate or flip the original image and the working image (if any) in the given direction, which is LayerSet.R90, .R180, .R270, .FLIP_VERTICAL or .FLIP_HORIZONTAL. Will remake the awt.Image only if it's loaded. The snapshot is NOT remade. */ // TODO this method CANNOT survive future requirements. Instead a full transformation always be taken care of ...
	public void rotatePixels(Patch patch, int direction) {
		synchronized (db_lock) {
			long id = patch.getId();
			ImagePlus imp_o = null;
			ImagePlus imp_w = fetchImagePlus(patch);
			lock(); // locking AFTER fetching the image (which also locks)
			if (!connectToDatabase()) {
				unlock();
				return;
			}
			InputStream i_stream = null;
			try {
				rotatePixels(imp_w, direction);
				ResultSet r = connection.prepareStatement("SELECT id FROM ab_patches WHERE tiff_working IS NOT NULL AND id=" + id).executeQuery();
				if (r.next()) {
					// just save normally, over tiff_working
					unlock();
					patch.updateInDatabase("tiff_working");
					lock();
					// get the original and rotate it as well
					releaseMemory();
					ResultSet ro = connection.prepareStatement("SELECT tiff_original FROM ab_patches WHERE id=" + id).executeQuery();
					if (ro.next()) {
						// retrieve the original
						i_stream = ro.getBinaryStream("tiff_original");
						imp_o = unzipTiff(i_stream, patch.getTitle());
						i_stream.close();
						// rotate pixels in the original
						rotatePixels(imp_o, direction);
						// overwrite original
						i_stream = createZippedStream(imp_o);
						PreparedStatement st = connection.prepareStatement("UPDATE ab_patches SET tiff_original=? WHERE id=" + id);
						st.setBinaryStream(1, i_stream, i_stream.available());
						st.executeUpdate();
						i_stream.close();
					}
					ro.close();
				} else {
					// there is no tiff_working, so overwrite tiff_original
					i_stream = createZippedStream(imp_w);
					PreparedStatement st = connection.prepareStatement("UPDATE ab_patches SET tiff_original=? WHERE id=" + id);
					st.setBinaryStream(1, i_stream, i_stream.available());
					st.executeUpdate();
					i_stream.close();
				}
				r.close();
				// now update the awt.Image, if loaded only!
				if (null != awts.get(id)) {
					awts.put(id, imp_w.getProcessor().createImage()); // flushes the old
				}
			} catch (Exception e) {
				new IJError(e);
			} finally {
				unlock();
			}
		}
	}

	private void rotatePixels(ImagePlus imp, int direction) {
		ImageProcessor ip = imp.getProcessor();
		StackProcessor sp;
		ImageStack is;
		Calibration cal;
		double tmp;
		switch (direction) {
			case LayerSet.FLIP_HORIZONTAL:
				ip.flipHorizontal();
				break;
			case LayerSet.FLIP_VERTICAL:
				ip.flipVertical();
				break;
			case LayerSet.R90:
				sp = new StackProcessor(imp.getStack(), ip);
				is = sp.rotateRight();
				cal = imp.getCalibration();
				imp.setStack(null, is);
				tmp = cal.pixelWidth;
				cal.pixelWidth = cal.pixelHeight;
				cal.pixelHeight = tmp;
				break;
			case LayerSet.R270:
				sp = new StackProcessor(imp.getStack(), ip);
				is = sp.rotateLeft();
				cal = imp.getCalibration();
				imp.setStack(null, is);
				tmp = cal.pixelWidth;
				cal.pixelWidth = cal.pixelHeight;
				cal.pixelHeight = tmp;
				break;
		}
	}

	//private Monitor monitor = null;
	//private MonitorQuitter quitter = null;
	//private Object monitor_ob = new Object();
	//private boolean monitor_in_use = false;

	/** Generate a modal frameless window that will block user input and report on connection dowloading state. If the argument is false, any existing monitor window is closed and input is enabled again. */
	/*
	public void monitor(boolean b) {
		synchronized (monitor_ob) {
			while (monitor_in_use) { try { monitor_ob.wait(); } catch (InterruptedException ie) {} }
			monitor_in_use = true;
			if (b && (null == monitor || !monitor.hasConnection(connection))) {
				System.out.println("new monitor");
				if (null != quitter) quitter.quit();
				monitor = new Monitor(connection);
				monitor.start();
				return;
			} else if (!b && null != monitor) {
				if (null != quitter) quitter.delay();
				else {
					quitter = new MonitorQuitter();
					quitter.start();
				}
			}
			monitor_in_use = false;
			monitor_ob.notifyAll();
		}
	}

	/** Monitors the InputStream of a PostgreSQL connection. This class is a tremendous hack on the PG JDBC that won't work on applets and certified systems. What it does: replaces the InputStream in the connection's PGStream with a ini.trakem2.utils.LoggingInputStream that keeps track of the ammount of bytes read and the speed. */
	private class Monitor extends Thread {

		private final Connection connection;
		private final LoggingInputStream lis;
		private java.awt.Dialog dialog;
		private boolean quit = false;
		private Label time;
		private Label speed;
		private Label bytes;
		private long last_shown = 0;

		private boolean hasConnection(Connection c) {
			return connection.equals(c);
		}

		private void makeWindow() {
			dialog = new java.awt.Dialog(IJ.getInstance(), "Loading...", false);
			dialog.setUndecorated(true);
			bytes = new Label("Loaded: 0 bytes                ");
			speed = new Label("Speed: 0 bytes/s               ");
			time = new Label ("Elapsed time: 0 s              ");
			dialog.setLayout(new GridLayout(3,1));
			dialog.addWindowListener(new java.awt.event.WindowAdapter() {
				public void windowDeactivated(java.awt.event.WindowEvent we) {
					dialog.toFront();
				}
			});
			dialog.add(time);
			dialog.add(bytes);
			dialog.add(speed);
			java.awt.Dimension screen = dialog.getToolkit().getScreenSize();
			dialog.pack();
			dialog.setLocation(screen.width/2 - dialog.getWidth()/2, screen.height/2 - dialog.getHeight()/2);
			//dialog.setVisible(true);
		}
		Monitor(Connection con) {
			connection = con;
			LoggingInputStream lis = null;
			try {
				AbstractJdbc2Connection a2 = (AbstractJdbc2Connection)connection;
				Class c2 = connection.getClass().getSuperclass().getSuperclass();
				java.lang.reflect.Field f_proto = c2.getDeclaredField("protoConnection");
				f_proto.setAccessible(true);
				// protoConnection is a ProtocolConnection interface, implemented in core.v3.ProtocolConnectionImpl !
				//ProtocolConnectionImpl pci = (ProtocolConnectionImpl)m_proto.get(c2); // class is private to the package, can't cast!
				Object pci = f_proto.get(a2);
				// finally, get the PGStream!
				java.lang.reflect.Field f_pgstream = pci.getClass().getDeclaredField("pgStream");
				f_pgstream.setAccessible(true);
				PGStream pgstream = (PGStream)f_pgstream.get(pci);
				// now the InputStream
				java.lang.reflect.Field f_i = pgstream.getClass().getDeclaredField("pg_input");
				f_i.setAccessible(true);
				InputStream stream = (InputStream)f_i.get(pgstream);
				lis = new LoggingInputStream(stream);
				f_i.set(pgstream, lis); // TADA! Many thanks to the PGSQL JDBC mailing list for this last tip on not just monitoring the PGStream as I was doing, but on replacing the inputstream altogether with a logging copy! ("CountingInputStream", they called it).

			} catch (Exception e) {
				new IJError(e);
			}
			this.lis = lis;
			makeWindow();
		}
		/** Stops the monitoring thread. */
		public void quit() {
			quit = true;
			//dialog.setModal(false);
			dialog.setVisible(false);
			dialog.dispose();
			dialog = null;
		}
		public void run() {
			long[] info = new long[5];
			while (!quit) {
				try {
					// check if this monitor has to die
					if (connection.isClosed()) {
						quit();
						return; 
					}

					// gather info
					lis.getInfo(info);
					long n_bytes = info[2];
					double d_speed = n_bytes / (double)info[1]; // in Kb / s
					long now = info[0];

					if (n_bytes > 1000) {
						time.setText("Elapsed time: " + Utils.cutNumber(info[3]/1000.0, 2) + " s");
						speed.setText("Speed: " + Utils.cutNumber(d_speed, 2) + " Kb/s");
						bytes.setText("Loaded: " + Utils.cutNumber(info[4]/1000.0, 2) + " Kb");
						if (!dialog.isVisible()) {
							//dialog.setModal(true); // block input // TODO this apparently blocks the current thread as well!
							Display.setReceivesInput(Project.findProject(DBLoader.this), false);
							dialog.setVisible(true);
							last_shown = now;
						}
						dialog.toFront();
					} else if (dialog.isVisible()) {
						//dialog.setModal(false); // enable user input
						time.setText("Elapsed time: " + Utils.cutNumber(info[3]/1000.0, 2) + " s");
						dialog.toFront();
						if (now - last_shown > 2000) {
							dialog.setVisible(false);
							Display.setReceivesInput(Project.findProject(DBLoader.this), true);
							lis.resetInfo();
						}
					}

					// read every second:
					Thread.sleep(500);

				} catch (InterruptedException ie) {
				} catch (Exception e) {
					new IJError(e);
					quit();
				}
			}
		}
	}

	/** Always returns false. */
	public boolean hasChanges() {
		return false;
	}

	public boolean isIdenticalProjectSource(final Loader loader) {
		if (loader instanceof DBLoader) {
			DBLoader dbl = (DBLoader)loader;
			if (this.db_host.equals(dbl.db_host)
			 && this.db_port.equals(dbl.db_port)) return true;
		}
		return false;
	}

	/** Affects only those set to true; the rest are left untouched. */
	public void restoreNodesExpandedState(final Project project) {
		//connect if disconnected
		if (!connectToDatabase()) {
			return;
		}
		
		try {
			final ProjectTree ptree = project.getProjectTree();
			final ResultSet r = connection.prepareStatement("SELECT id,expanded from ab_things where project_id=" + project.getId()).executeQuery();
			while (r.next()) {
				boolean expanded = r.getBoolean(2); //"expanded");
				if (expanded) {
					Thing thing = project.find(r.getLong(1)); //DNDTree.findNode(id, ptree);
					if (null != thing) ptree.setExpandedSilently(thing, true);
				}
			}
			ptree.updateUI();
		} catch (Exception e) {
			new IJError(e);
		}
	}

	/** Returns the last Patch. */
	protected Patch importStackAsPatches(final Project project, final Layer first_layer, final ImagePlus imp_stack, final boolean as_copy, final String filepath) {
		int pos_x = (int)first_layer.getLayerWidth()/2 - imp_stack.getWidth()/2;
		int pos_y = (int)first_layer.getLayerHeight()/2 - imp_stack.getHeight()/2;
		final double thickness = first_layer.getThickness();
		final String title = Utils.removeExtension(imp_stack.getTitle()).replace(' ', '_');
		Utils.showProgress(0);
		Patch previous_patch = null;
		final int n = imp_stack.getStackSize();
		for (int i=1; i<=n; i++) {
			Layer layer = first_layer;
			double z = first_layer.getZ() + (i-1) * thickness;
			if (i > 1) layer = first_layer.getParent().getLayer(z, thickness, true); // will create new layer if not found
			if (null == layer) {
				Utils.log("Display.importStack: could not create new layers.");
				return null;
			}
			ImageProcessor ip = imp_stack.getStack().getProcessor(i);
			if (as_copy) ip = ip.duplicate();
			ImagePlus imp_patch_i = new ImagePlus(title + "__slice=" + i, ip);
			String label = imp_stack.getStack().getSliceLabel(i);
			if (null == label) label = "";
			Patch patch = new Patch(project, label + " " + title + " " + i, pos_x, pos_y, imp_patch_i);
			layer.add(patch);
			if (null != previous_patch) patch.link(previous_patch);
			previous_patch = patch;
			Utils.showProgress(i * (1.0 / n));
		}
		Utils.showProgress(1.0);
		// return the last Patch
		return previous_patch;
	}
}
