importClass( Packages.mpicbg.trakem2.transform.MovingLeastSquaresTransform );

IJ.log( ">>> Test mpicbg.trakem2.transform.MovingLeastSquaresTransform >>>" );

var l1 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
l1[ 0 ] = 40;
l1[ 1 ] = 40;

var l2 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
l2[ 0 ] = 300;
l2[ 1 ] = 200;

var l3 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
l3[ 0 ] = 300;
l3[ 1 ] = 40;

var w1 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
w1[ 0 ] = 80;
w1[ 1 ] = 80;

var w2 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
w2[ 0 ] = 260;
w2[ 1 ] = 160;

var w3 = new java.lang.reflect.Array.newInstance( java.lang.Float.TYPE, 2 );
w3[ 0 ] = 240;
w3[ 1 ] = 80;

var dataString =
	"rigid 1 " +
	l1[ 0 ] + " " + l1[ 1 ] + " " +
	w1[ 0 ] + " " + w1[ 1 ] + " " +
	l2[ 0 ] + " " + l2[ 1 ] + " " +
	w2[ 0 ] + " " + w2[ 1 ] + " " +
	l3[ 0 ] + " " + l3[ 1 ] + " " +
	w3[ 0 ] + " " + w3[ 1 ];
	
var m = new MovingLeastSquaresTransform();
m.init( dataString );

var l1p = m.apply( l1 );
var l2p = m.apply( l2 );
var l3p = m.apply( l3 );

if (
	w1[ 0 ] == l1p[ 0 ] &&
	w1[ 1 ] == l1p[ 1 ] &&
	w2[ 0 ] == l2p[ 0 ] &&
	w2[ 1 ] == l2p[ 1 ] &&
	w3[ 0 ] == l3p[ 0 ] &&
	w3[ 1 ] == l3p[ 1 ] )
	IJ.log( " + transferring control points correctly passed" )	
else
	IJ.error( " - transferring control points correctly failed" )	

IJ.log( "<<< Test mpicbg.trakem2.transform.MovingLeastSquaresTransform <<<" );

//IJ.run( "Clown (14K)" );

