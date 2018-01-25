package edu.illinois.library.cantaloupe.resource;

import edu.illinois.library.cantaloupe.image.MediaType;
import edu.illinois.library.cantaloupe.util.Stopwatch;
import org.apache.commons.io.IOUtils;
import org.restlet.data.Disposition;
import org.restlet.representation.OutputRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Representation for cached images.
 */
public class CachedImageRepresentation extends OutputRepresentation {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CachedImageRepresentation.class);

    private InputStream inputStream;

    /**
     * Constructor for images from the cache.
     *
     * @param inputStream Cache input stream.
     * @param mediaType   Media type of the cached image.
     * @param disposition HTTP {@literal Content-Disposition}.
     */
    public CachedImageRepresentation(InputStream inputStream,
                                     MediaType mediaType,
                                     Disposition disposition) {
        super(new org.restlet.data.MediaType(mediaType.toString()));
        this.inputStream = inputStream;
        setDisposition(disposition);
    }

    /**
     * Writes the cached image to the given output stream.
     *
     * @param outputStream Response body stream supplied by Restlet.
     */
    @Override
    public void write(OutputStream outputStream) throws IOException {
        try {
            final Stopwatch watch = new Stopwatch();
            IOUtils.copy(inputStream, outputStream);
            LOGGER.debug("Streamed from the cache without resolving in {} msec",
                    watch.timeElapsed());
        } finally {
            inputStream.close();
            // N.B.: Restlet will close the output stream.
        }
    }

}