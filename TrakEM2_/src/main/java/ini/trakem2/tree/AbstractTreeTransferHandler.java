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


import java.awt.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.dnd.*;
import java.awt.datatransfer.*;
import java.awt.image.*;
 
/** Adapted from freely available code by DeuDeu from http://forum.java.sun.com/thread.jspa?threadID=296255&start=0&tstart=0 */
public abstract class AbstractTreeTransferHandler implements DragGestureListener, DragSourceListener, DropTargetListener {
 
        private DNDTree tree;
        private DragSource dragSource;
        private DropTarget dropTarget;
        private static DefaultMutableTreeNode draggedNode; 
        private DefaultMutableTreeNode draggedNodeParent; 
        private static BufferedImage image = null; //buff image
        private Rectangle rect2D = new Rectangle();
        private boolean drawImage;
        private DragGestureRecognizer dgr;
 
        protected AbstractTreeTransferHandler(DNDTree tree, int action, boolean drawIcon) {
            this.tree = tree;
            drawImage = drawIcon;

            if (!GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance())
            {
                dragSource = new DragSource();
                dgr = dragSource.createDefaultDragGestureRecognizer(tree, action, this);
                dropTarget = new DropTarget(tree, action, this);
            }
            else
            {
                dragSource = null;
                dgr = null;
                dropTarget = null;
            }
        }

        protected void destroy() {
            if (dgr != null)
            {
                dgr.removeDragGestureListener(this);
                dgr = null;
            }
        	tree = null; // friggin' memory leak
        	dragSource = null;
            if (dropTarget != null)
            {
        	    dropTarget.removeDropTargetListener(this);
                dropTarget = null;
            }

        	draggedNode = null;
        	draggedNodeParent = null;
        	image = null;
        	rect2D = null;
        }

        /* Methods for DragSourceListener */
        public void dragDropEnd(DragSourceDropEvent dsde) {
        	if (dsde.getDropSuccess() && dsde.getDropAction()==DnDConstants.ACTION_MOVE && draggedNodeParent != null) {
        		((DefaultTreeModel)tree.getModel()).nodeStructureChanged(draggedNodeParent);
        		tree.expandPath(new TreePath(draggedNodeParent.getPath()));
        		tree.expandPath(new TreePath(draggedNode.getPath()));
        	}

        }
        public final void dragEnter(DragSourceDragEvent dsde)  {
                int action = dsde.getDropAction();
                if (action == DnDConstants.ACTION_COPY)  {
                        dsde.getDragSourceContext().setCursor(DragSource.DefaultCopyDrop);
                } 
                else {
                        if (action == DnDConstants.ACTION_MOVE) {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveDrop);
                        } 
                        else {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
                        }
                }
        }
        public final void dragOver(DragSourceDragEvent dsde) {
                int action = dsde.getDropAction();
                if (action == DnDConstants.ACTION_COPY) {
                        dsde.getDragSourceContext().setCursor(DragSource.DefaultCopyDrop);
                } 
                else  {
                        if (action == DnDConstants.ACTION_MOVE) {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveDrop);
                        } 
                        else  {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
                        }
                }
        }
        public final void dropActionChanged(DragSourceDragEvent dsde)  {
                int action = dsde.getDropAction();
                if (action == DnDConstants.ACTION_COPY) {
                        dsde.getDragSourceContext().setCursor(DragSource.DefaultCopyDrop);
                }
                else  {
                        if (action == DnDConstants.ACTION_MOVE) {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveDrop);
                        } 
                        else {
                                dsde.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
                        }
                }
        }

        public final void dragExit(DragSourceEvent dse) {
		dse.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
        }       
                
        /* Methods for DragGestureListener */
        public final void dragGestureRecognized(DragGestureEvent dge) {
                TreePath path = tree.getSelectionPath(); 
                if (path != null) { 
                        draggedNode = (DefaultMutableTreeNode)path.getLastPathComponent();
                        draggedNodeParent = (DefaultMutableTreeNode)draggedNode.getParent();
                        if (drawImage) {
                                Rectangle pathBounds = tree.getPathBounds(path); //getpathbounds of selectionpath
                                JComponent lbl = (JComponent)tree.getCellRenderer().getTreeCellRendererComponent(tree, draggedNode, false , tree.isExpanded(path),((DefaultTreeModel)tree.getModel()).isLeaf(path.getLastPathComponent()), 0,false);//returning the label
                                lbl.setBounds(pathBounds);//setting bounds to lbl
                                image = new BufferedImage(lbl.getWidth(), lbl.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);//buffered image reference passing the label's ht and width
                                Graphics2D graphics = image.createGraphics();//creating the graphics for buffered image
                                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));       //Sets the Composite for the Graphics2D context
                                lbl.setOpaque(false);
                                lbl.paint(graphics); //painting the graphics to label
                                graphics.dispose();                             
                        }
                        dragSource.startDrag(dge, DragSource.DefaultMoveNoDrop , image, new Point(0,0), new TransferableNode(draggedNode), this);                       
                }        
        }
 
        /* Methods for DropTargetListener */
 
        public final void dragEnter(DropTargetDragEvent dtde) {
                Point pt = dtde.getLocation();
                int action = dtde.getDropAction();
                if (drawImage) {
                        paintImage(pt);
                }
                if (canPerformAction(tree, draggedNode, action, pt)) {
                        dtde.acceptDrag(action);                        
                }
                else {
                        dtde.rejectDrag();
                }
        }
 
        public final void dragExit(DropTargetEvent dte) {
                if (drawImage) {
                        clearImage();
                }
        }
 
        public final void dragOver(DropTargetDragEvent dtde) {
                Point pt = dtde.getLocation();
                int action = dtde.getDropAction();
                tree.autoscroll(pt);
                if (drawImage) {
                        paintImage(pt);
                }
                if (canPerformAction(tree, draggedNode, action, pt)) {
                        dtde.acceptDrag(action);                        
                }
                else {
                        dtde.rejectDrag();
                }
        }
 
        public final void dropActionChanged(DropTargetDragEvent dtde) {
                Point pt = dtde.getLocation();
                int action = dtde.getDropAction();
                if (drawImage) {
                        paintImage(pt);
                }
                if (canPerformAction(tree, draggedNode, action, pt)) {
                        dtde.acceptDrag(action);                        
                }
                else {
                        dtde.rejectDrag();
                }
        }
 
        public final void drop(DropTargetDropEvent dtde) {
                try {
                        if (drawImage) {
                                clearImage();
                        }
                        int action = dtde.getDropAction();
                        Transferable transferable = dtde.getTransferable();
                        Point pt = dtde.getLocation();
                        if (transferable.isDataFlavorSupported(TransferableNode.NODE_FLAVOR) && canPerformAction(tree, draggedNode, action, pt)) {
                                TreePath pathTarget = tree.getPathForLocation(pt.x, pt.y);
                                DefaultMutableTreeNode node = (DefaultMutableTreeNode) transferable.getTransferData(TransferableNode.NODE_FLAVOR);
                                DefaultMutableTreeNode newParentNode = (DefaultMutableTreeNode)pathTarget.getLastPathComponent();
                                if (executeDrop(tree, node, newParentNode, action)) {
                                        dtde.acceptDrop(action);
                                        dtde.dropComplete(true);
                                        return;                                 
                                }
                        }
                        dtde.rejectDrop();
                        dtde.dropComplete(false);
                }
                catch (Exception e) {   
                        System.out.println(e);
                        dtde.rejectDrop();
                        dtde.dropComplete(false);
                }
        }
 
        private final void paintImage(Point pt) {
		if (null == rect2D || null == image) {
			return;
		}
                tree.paintImmediately(rect2D.getBounds());
                rect2D.setRect((int) pt.getX(),(int) pt.getY(),image.getWidth(),image.getHeight());
                tree.getGraphics().drawImage(image,(int) pt.getX(),(int) pt.getY(),tree);
        }
 
        private final void clearImage() {
                tree.paintImmediately(rect2D.getBounds());
        }
 
        public abstract boolean canPerformAction(DNDTree target, DefaultMutableTreeNode nodeDragged, int action, Point location);
 
        public abstract boolean executeDrop(DNDTree target, DefaultMutableTreeNode nodeDragged, DefaultMutableTreeNode newParentNode, int action);
}
