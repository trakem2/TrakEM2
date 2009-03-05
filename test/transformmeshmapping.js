importClass( Packages.mpicbg.trakem2.transform.MovingLeastSquaresTransform );
importClass( Packages.mpicbg.trakem2.transform.TransformMesh );
importClass( Packages.mpicbg.trakem2.transform.TransformMeshMapping );
importClass( Packages.java.awt.Color );

IJ.log( ">>> Test mpicbg.trakem2.transform.TransformMeshMapping >>>" );

var m = new MovingLeastSquaresTransform();
m.init( "rigid 1 40 40 80 80 280 160 220 140 280 40 200 80" );
IJ.run( "Clown (14K)" );
var imp = IJ.getImage();
for ( var i = 1; i <= 6; ++i )
{
	var n = Math.pow( 2, i );
	IJ.log( n );
	var mesh = new TransformMesh( m, n, imp.getWidth(), imp.getHeight() );
	var mapping = new TransformMeshMapping( mesh );
	var impMapping = new ImagePlus( n + " mapping", mapping.createMappedImage( imp.getProcessor() ) );
	var impMappingInterpolated = new ImagePlus( n + " interpolated mapping", mapping.createMappedImageInterpolated( imp.getProcessor() ) );
	impMapping.show();
	impMappingInterpolated.show();
	impMapping.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
	impMappingInterpolated.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
}

IJ.log( " + mapping passed" )	
IJ.log( "<<< Test mpicbg.trakem2.transform.TransformMeshMapping <<<" );
