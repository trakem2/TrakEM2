importClass( Packages.lenscorrection.DistortionCorrectionTask );
importClass( Packages.ini.trakem2.display.Display );
importClass( Packages.ini.trakem2.display.Patch );
importPackage( Packages.mpicbg.trakem2.transform );

/** Remove all coordinate transforms from all patches in the layer set */
var layers = Display.getFront().getSelection().getLayer().getParent().getLayers();
for ( var i = 0; i < layers.size(); ++i )
{
	var patches = layers.get( i ).getDisplayables( Patch );
	for ( var j = 0; j < patches.size(); ++j )
	{
		var patch = patches.get( j );
		patch.setCoordinateTransform( null );
		patch.updateMipmaps();
	}
}

/** Apply a distortion */
//var m = new MovingLeastSquaresTransform();
//m.init( "rigid 1 100 100 200 200 1150 1150 1050 1050 1150 100 950 100" );
//var mi = new MovingLeastSquaresTransform();
//mi.init( "rigid 1 200 200 100 100 1050 1050 1150 1150 950 100 1150 100" );
//m.init( "rigid 1 100 100 120 120 1150 1150 1130 1130 1150 100 1130 100" );
var m = new TranslationModel2D();
m.init( "400 200" );
for ( var i = 0; i < layers.size(); ++i )
{
	var patches = layers.get( i ).getDisplayables( Patch );
	for ( var j = 0; j < patches.size(); ++j )
	{
		var patch = patches.get( j );
		patch.setCoordinateTransform( m );
		patch.updateMipmaps();
	}
}

Display.repaint();


/** Estimate and apply distortion correction model */
DistortionCorrectionTask.correctDistortionFromSelection( Display.getFront().getSelection() );

