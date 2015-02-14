/**
 *
 */
package mpicbg.trakem2.align;

import ini.trakem2.display.Patch;

import java.awt.geom.AffineTransform;

import mpicbg.models.TranslationModel2D;

public class TranslationTile2D extends AbstractAffineTile2D< mpicbg.models.TranslationModel2D >
{
	public TranslationTile2D( final mpicbg.models.TranslationModel2D model, final Patch patch )
	{
		super( model, patch );
	}

	public TranslationTile2D( final Patch patch )
	{
		this( new TranslationModel2D(), patch );
	}

	/**
	 * Initialize the model with the parameters of the {@link AffineTransform}
	 * of the {@link Patch}.  The {@link AffineTransform} should be a
	 * Translation, otherwise the results will not be what you might expect.
	 * This means, that:
	 * <pre>
	 *   {@link AffineTransform#getScaleX()} == {@link AffineTransform#getScaleY()} == 1
	 *   {@link AffineTransform#getShearX()} == {@link AffineTransform#getShearY()} == 0
	 * </pre>
	 */
	@Override
	protected void initModel()
	{
		final AffineTransform a = patch.getAffineTransform();
		model.set( a.getTranslateX(), a.getTranslateY() );
	}

}
