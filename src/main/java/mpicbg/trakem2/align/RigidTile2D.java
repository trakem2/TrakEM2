/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2025 Albert Cardona, Stephan Saalfeld and others.
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

import mpicbg.models.RigidModel2D;

public class RigidTile2D extends AbstractAffineTile2D< mpicbg.models.RigidModel2D >
{
	public RigidTile2D( final mpicbg.models.RigidModel2D model, final Patch patch )
	{
		super( model, patch );
	}
	
	public RigidTile2D( final Patch patch )
	{
		this( new RigidModel2D(), patch );
	}
	
	/**
	 * Initialize the model with the parameters of the {@link AffineTransform}
	 * of the {@link Patch}.  The {@link AffineTransform} should be a
	 * Rigid Transformation, otherwise the results will not be what you might
	 * expect.  This means, that:
	 * <ul>
	 * <li>{@link AffineTransform#getScaleX()} == {@link AffineTransform#getScaleY()}</li>
	 * <li>{@link AffineTransform#getShearX()} == -{@link AffineTransform#getShearY()}</li>
	 * <li>{@link AffineTransform#getScaleX()}<sup>2</sup> + {@link AffineTransform#getShearX()}<sup>2</sup> == 1</li>
	 * </ul>
	 */
	@Override
	protected void initModel()
	{
		final AffineTransform a = patch.getAffineTransform();
		model.set( ( float )a.getScaleX(), ( float )a.getShearY(), ( float )a.getTranslateX(), ( float )a.getTranslateY() );
	}

}
