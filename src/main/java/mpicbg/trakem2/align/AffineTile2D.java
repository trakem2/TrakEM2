/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
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
import mpicbg.models.AffineModel2D;

public class AffineTile2D extends AbstractAffineTile2D< AffineModel2D >
{
    private static final long serialVersionUID = -6891778987703462049L;

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
