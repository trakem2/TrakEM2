package ini.trakem2.persistence;

/** Represent an image with byte arrays:
 * 1 channel: grey
 * 2 channels: grey + alpha
 * 3 channels: RGB
 * 4 channels: RGBA
 * 
 * @author Albert Cardona
 *
 */
public final class ImageBytes
{
	public final byte[][] c;
	public final int width, height;
	
	public ImageBytes(final byte[][] c, final int width, final int height) {
		this.c = c;
		this.width = width;
		this.height = height;
	}
}
