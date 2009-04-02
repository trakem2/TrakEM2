importClass( Packages.lenscorrection.DistortionCorrectionTask );
importClass( Packages.ini.trakem2.display.Display );
importClass( Packages.ini.trakem2.display.Patch );
importPackage( Packages.mpicbg.trakem2.transform );

/** Estimate and apply distortion correction model */
DistortionCorrectionTask.correctDistortionFromSelection( Display.getFront().getSelection() );
Display.repaint();
