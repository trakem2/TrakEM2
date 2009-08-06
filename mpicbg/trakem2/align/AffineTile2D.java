/**
 * 
 */
package mpicbg.trakem2.align;

import mpicbg.trakem2.transform.AffineModel2D;
import ini.trakem2.display.Patch;

public class AffineTile2D extends AbstractAffineTile2D< mpicbg.models.AffineModel2D >
{
	public AffineTile2D( final AffineModel2D model, final Patch patch )
	{
		super( model, patch );
	}
	
	public AffineTile2D( final Patch patch )
	{
		this( new AffineModel2D(), patch );
	}
	
	@Override
	protected void initModel()
	{
		model.set( patch.getAffineTransform() );
	}

}
