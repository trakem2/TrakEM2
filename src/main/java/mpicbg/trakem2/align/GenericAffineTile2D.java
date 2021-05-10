/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
