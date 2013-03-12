package ini.trakem2.persistence;

import ij.process.FloatProcessor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * A metaphorical Self Addressed Stamped Envelope for a FloatProcessor. Allows FloatProcessors to
 * be Serialized.
 */
public class FloatProcessorSASE implements Serializable
{
    private transient FloatProcessor processor;
    private final float[] pixels;
    private final int width, height;

    public FloatProcessorSASE(FloatProcessor fp)
    {
        processor = fp;
        width = processor.getWidth();
        height = processor.getHeight();
        pixels = (float[])processor.getPixels();
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException
    {
        ois.defaultReadObject();
        processor = new FloatProcessor(width, height, pixels);
    }

    public FloatProcessor getData()
    {
        return processor;
    }
}
