package mpicbg.trakem2.util;

import java.io.Serializable;

public class Triple<A, B, C> implements Serializable
{
	public final A a;
	public final B b;
	public final C c;

	public Triple(final A a, final B b, final C c)
	{
		this.a = a;
		this.b = b;
		this.c = c;
	}
}
