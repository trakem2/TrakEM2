importClass( Packages.mpicbg.trakem2.transform.MovingLeastSquaresTransform );
importClass( Packages.mpicbg.trakem2.transform.TransformMesh );
importClass( Packages.mpicbg.trakem2.transform.PrecomputedTransformMesh );
importClass( Packages.mpicbg.ij.InvertibleTransformMapping );
importClass( Packages.java.awt.Color );

IJ.log( ">>> Test mpicbg.trakem2.transform.PrecomputedTransformMesh >>>" );

var m = new MovingLeastSquaresTransform();
m.init( "rigid 1 40 40 80 80 280 160 220 140 280 40 200 80" );
IJ.run( "Clown (14K)" );
var imp = IJ.getImage();
for ( var i = 4; i <= 4; ++i )
{
	var n = Math.pow( 2, i );
	IJ.log( n );
	var mesh = new TransformMesh( m, n, imp.getWidth(), imp.getHeight() );
	var precomputedMesh = new PrecomputedTransformMesh( m, n, imp.getWidth(), imp.getHeight() );
	var mapping = new InvertibleTransformMapping( mesh );
	var precomputedMapping = new InvertibleTransformMapping( precomputedMesh );
	
	var ipMapping = imp.getProcessor().createProcessor( imp.getProcessor().getWidth(), imp.getProcessor().getHeight() );
	mapping.map( imp.getProcessor(), ipMapping );
	var ipMappingInterpolated = imp.getProcessor().createProcessor( imp.getProcessor().getWidth(), imp.getProcessor().getHeight() );
	mapping.mapInterpolated( imp.getProcessor(), ipMappingInterpolated );
	var ipPrecomputedMapping = imp.getProcessor().createProcessor( imp.getProcessor().getWidth(), imp.getProcessor().getHeight() );
	precomputedMapping.map( imp.getProcessor(), ipPrecomputedMapping );
	var ipPrecomputedMappingInterpolated = imp.getProcessor().createProcessor( imp.getProcessor().getWidth(), imp.getProcessor().getHeight() );
	precomputedMapping.mapInterpolated( imp.getProcessor(), ipPrecomputedMappingInterpolated );
	
	var impMapping = new ImagePlus( n + " mapping", ipMapping );
	var impMappingInterpolated = new ImagePlus( n + " interpolated mapping", ipMappingInterpolated );
	var impPrecomputedMapping = new ImagePlus( n + " precomputed mapping", ipPrecomputedMapping );
	var impPrecomputedMappingInterpolated = new ImagePlus( n + " interpolated precomputed mapping", ipPrecomputedMappingInterpolated );
	
	impMapping.show();
	impMappingInterpolated.show();
	impMapping.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
	impMappingInterpolated.getCanvas().setDisplayList( mesh.illustrateMesh(), Color.white, null );
	
	impPrecomputedMapping.show();
	impPrecomputedMappingInterpolated.show();
	impPrecomputedMapping.getCanvas().setDisplayList( precomputedMesh.illustrateMesh(), Color.white, null );
	impPrecomputedMappingInterpolated.getCanvas().setDisplayList( precomputedMesh.illustrateMesh(), Color.white, null );
	
	
/*	
	var ipMappingInverse = mapping.createInverseMappedImage( ipMapping );
	var ipMappingInverseInterpolated = mapping.createInverseMappedImageInterpolated( ipMappingInterpolated );
	
	var impMappingInverse = new ImagePlus( n + " inverse mapping", ipMappingInverse );
	var impMappingInverseInterpolated = new ImagePlus( n + " interpolated inverse mapping", ipMappingInverseInterpolated );
	impMappingInverse.show();
	impMappingInverseInterpolated.show();
*/	
}

IJ.log( " + mapping passed" )	
IJ.log( "<<< Test mpicbg.trakem2.transform.PreomutedTransformMeshMapping <<<" );
