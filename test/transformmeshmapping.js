importClass( Packages.mpicbg.trakem2.transform.MovingLeastSquaresTransform );
importClass( Packages.mpicbg.trakem2.transform.TransformMesh );
importClass( Packages.mpicbg.trakem2.transform.TransformMeshMapping );
importClass( Packages.java.awt.Color );

IJ.log( ">>> Test mpicbg.trakem2.transform.TransformMeshMapping >>>" );

var m = new MovingLeastSquaresTransform();
m.init( "rigid 2 1 40 40 80 80 1 280 160 220 140 1 280 40 200 80 1" );
IJ.run( "Clown (14K)" );
var imp = IJ.getImage();
for ( var i = 1; i <= 4; ++i )
{
	var n = Math.pow( 2, i );
	IJ.log( n );
	var mesh = new TransformMesh( m, n, imp.getWidth(), imp.getHeight() );
	var mapping = new TransformMeshMapping( mesh );
	
	var ipMapping = mapping.createMappedImage( imp.getProcessor() );
	var ipMappingInterpolated = mapping.createMappedImageInterpolated( imp.getProcessor() );
	
	var impMapping = new ImagePlus( n + " mapping", ipMapping );
	var impMappingInterpolated = new ImagePlus( n + " interpolated mapping", ipMappingInterpolated );
	
	impMapping.show();
	impMappingInterpolated.show();
	impMapping.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
	impMappingInterpolated.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
	
	var ipMappingInverse = mapping.createInverseMappedImage( ipMapping );
	var ipMappingInverseInterpolated = mapping.createInverseMappedImageInterpolated( ipMappingInterpolated );
	
	var impMappingInverse = new ImagePlus( n + " inverse mapping", ipMappingInverse );
	var impMappingInverseInterpolated = new ImagePlus( n + " interpolated inverse mapping", ipMappingInverseInterpolated );
	impMappingInverse.show();
	impMappingInverseInterpolated.show();
	
}

IJ.log( " + mapping passed" )	
IJ.log( "<<< Test mpicbg.trakem2.transform.TransformMeshMapping <<<" );
