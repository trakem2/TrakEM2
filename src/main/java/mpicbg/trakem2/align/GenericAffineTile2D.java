/**
 *
 */
package mpicbg.trakem2.align;

import ini.trakem2.display.Patch;

import java.awt.geom.AffineTransform;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

public class GenericAffineTile2D< A extends Model< A > & Affine2D< A > > extends AbstractAffineTile2D< A >
{
    private static final long serialVersionUID = 5632120826192026830L;

    public GenericAffineTile2D( final A model, final Patch patch )
	{
		super( model, patch );
	}

	@SuppressWarnings( "rawtypes" )
	@Override
	protected void initModel()
	{
		final AffineTransform a = patch.getAffineTransform();
		if ( AffineModel2D.class.isInstance( model ) )
			( ( AffineModel2D )( Object )model ).set( a );
		else if ( SimilarityModel2D.class.isInstance( model ) )
			( ( SimilarityModel2D )( Object )model ).set( a.getScaleX(), a.getShearY(), a.getTranslateX(), a.getTranslateY() );
		else if ( RigidModel2D.class.isInstance( model ) )
			( ( RigidModel2D )( Object )model ).set( a.getScaleX(), a.getShearY(), a.getTranslateX(), a.getTranslateY() );
		else if ( TranslationModel2D.class.isInstance( model ) )
			( ( TranslationModel2D )( Object )model ).set( a.getTranslateX(), a.getTranslateY() );
		else if ( InterpolatedAffineModel2D.class.isInstance( model ) )
			( ( InterpolatedAffineModel2D )( Object )model ).set( a );
	}

}
