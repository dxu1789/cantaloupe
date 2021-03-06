package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.async.ThreadPool;
import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Info;
import edu.illinois.library.cantaloupe.image.Metadata;
import edu.illinois.library.cantaloupe.image.Orientation;
import edu.illinois.library.cantaloupe.operation.ColorTransform;
import edu.illinois.library.cantaloupe.operation.Crop;
import edu.illinois.library.cantaloupe.operation.Encode;
import edu.illinois.library.cantaloupe.operation.MetadataCopy;
import edu.illinois.library.cantaloupe.operation.Operation;
import edu.illinois.library.cantaloupe.operation.OperationList;
import edu.illinois.library.cantaloupe.operation.ReductionFactor;
import edu.illinois.library.cantaloupe.operation.Rotate;
import edu.illinois.library.cantaloupe.operation.Scale;
import edu.illinois.library.cantaloupe.operation.Sharpen;
import edu.illinois.library.cantaloupe.operation.Transpose;
import edu.illinois.library.cantaloupe.operation.overlay.Overlay;
import edu.illinois.library.cantaloupe.operation.redaction.Redaction;
import edu.illinois.library.cantaloupe.processor.codec.BufferedImageSequence;
import edu.illinois.library.cantaloupe.processor.codec.ImageWriter;
import edu.illinois.library.cantaloupe.processor.codec.ImageWriterFactory;
import edu.illinois.library.cantaloupe.processor.codec.ReaderHint;
import edu.illinois.library.cantaloupe.resource.iiif.ProcessorFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>Provides generic functionality for applying transformations to {@link
 * BufferedImage}s and {@link BufferedImageSequence}s using Java 2D.</p>
 *
 * <p>This class could be thought of as a higher-level interface than the
 * various {@link Java2DUtil} methods.</p>
 *
 * @since 4.1
 */
final class Java2DPostProcessor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Java2DPostProcessor.class);

    static final Set<ProcessorFeature> SUPPORTED_FEATURES =
            Collections.unmodifiableSet(EnumSet.of(
                    ProcessorFeature.MIRRORING,
                    ProcessorFeature.REGION_BY_PERCENT,
                    ProcessorFeature.REGION_BY_PIXELS,
                    ProcessorFeature.REGION_SQUARE,
                    ProcessorFeature.ROTATION_ARBITRARY,
                    ProcessorFeature.ROTATION_BY_90S,
                    ProcessorFeature.SIZE_ABOVE_FULL,
                    ProcessorFeature.SIZE_BY_DISTORTED_WIDTH_HEIGHT,
                    ProcessorFeature.SIZE_BY_FORCED_WIDTH_HEIGHT,
                    ProcessorFeature.SIZE_BY_HEIGHT,
                    ProcessorFeature.SIZE_BY_PERCENT,
                    ProcessorFeature.SIZE_BY_WIDTH,
                    ProcessorFeature.SIZE_BY_WIDTH_HEIGHT));
    static final Set<edu.illinois.library.cantaloupe.resource.iiif.v1.Quality>
            SUPPORTED_IIIF_1_QUALITIES = Collections.unmodifiableSet(EnumSet.of(
            edu.illinois.library.cantaloupe.resource.iiif.v1.Quality.BITONAL,
            edu.illinois.library.cantaloupe.resource.iiif.v1.Quality.COLOR,
            edu.illinois.library.cantaloupe.resource.iiif.v1.Quality.GREY,
            edu.illinois.library.cantaloupe.resource.iiif.v1.Quality.NATIVE));
    static final Set<edu.illinois.library.cantaloupe.resource.iiif.v2.Quality>
            SUPPORTED_IIIF_2_QUALITIES = Collections.unmodifiableSet(EnumSet.of(
            edu.illinois.library.cantaloupe.resource.iiif.v2.Quality.BITONAL,
            edu.illinois.library.cantaloupe.resource.iiif.v2.Quality.COLOR,
            edu.illinois.library.cantaloupe.resource.iiif.v2.Quality.DEFAULT,
            edu.illinois.library.cantaloupe.resource.iiif.v2.Quality.GRAY));

    /**
     * Convenience method. Can be used for all images but not {@link
     * BufferedImageSequence image sequences}; for those, use {@link
     * #postProcess(BufferedImageSequence, OperationList, Info, Metadata,
     * OutputStream)}.
     *
     * @param image           Image to process.
     * @param readerHints     Hints from the image reader. May be
     *                        {@literal null}.
     * @param opList          Operations to apply to the image.
     * @param imageInfo       Information about the source image.
     * @param reductionFactor May be {@literal null}.
     * @param metadata        Metadata to embed in the resulting image.
     * @param outputStream    Output stream to write the resulting image to.
     */
    static void postProcess(BufferedImage image,
                            final Set<ReaderHint> readerHints,
                            final OperationList opList,
                            final Info imageInfo,
                            final ReductionFactor reductionFactor,
                            final Metadata metadata,
                            final OutputStream outputStream) throws IOException {
        image = doPostProcess(image, readerHints, opList, imageInfo,
                reductionFactor);

        ImageWriter writer = new ImageWriterFactory()
                .newImageWriter((Encode) opList.getFirst(Encode.class));
        if (opList.getFirst(MetadataCopy.class) != null) {
            writer.setMetadata(metadata);
        }
        writer.write(image, outputStream);
    }

    /**
     * Variant for processing {@link BufferedImageSequence image sequences},
     * such as to support animated GIFs.
     *
     * @param sequence     Sequence containing one or more images, which will
     *                     be replaced with the post-processed versions.
     * @param opList       Operations to apply to each image in the sequence.
     * @param info         Information about the source image.
     * @param metadata     Metadata to embed in the resulting image.
     * @param outputStream Stream to write the resulting image to.
     * @throws IllegalArgumentException if the sequence is empty.
     */
    static void postProcess(final BufferedImageSequence sequence,
                            final OperationList opList,
                            final Info info,
                            final Metadata metadata,
                            final OutputStream outputStream) throws IOException {
        final int numFrames = sequence.length();

        // 1. If the sequence contains no frames, throw an exception.
        // 2. If it contains only one frame, process the frame in the current
        //    thread.
        // 3. If it contains more than one frame, spread the work across one
        //    thread per CPU.
        if (numFrames < 1) {
            throw new IllegalArgumentException("Empty sequence");
        } else if (numFrames == 1) {
            BufferedImage image = sequence.get(0);
            image = processFrame(image, opList, info);
            sequence.set(0, image);
        } else {
            final int numThreads = Math.min(
                    numFrames, Runtime.getRuntime().availableProcessors());
            final int framesPerThread =
                    (int) Math.ceil(numFrames / (float) numThreads);
            final CountDownLatch latch = new CountDownLatch(numFrames);

            LOGGER.debug("Processing {} frames in {} threads ({} frames per thread)",
                    numFrames, numThreads, framesPerThread);

            // Create a list containing numThreads queues. Each map will
            // contain { "frame": int, "image": BufferedImage }.
            final List<Queue<Map<String,Object>>> processingQueues =
                    new ArrayList<>(numThreads);
            for (short thread = 0; thread < numThreads; thread++) {
                final Queue<Map<String,Object>> queue = new LinkedList<>();
                processingQueues.add(queue);

                final int startFrame = thread * framesPerThread;
                final int endFrame = Math.min(startFrame + framesPerThread,
                        numFrames);
                for (int frameNum = startFrame; frameNum < endFrame; frameNum++) {
                    Map<String,Object> map = new HashMap<>();
                    map.put("frame", frameNum);
                    map.put("image", sequence.get(frameNum));
                    queue.add(map);
                }
            }

            // Process each queue in a separate thread.
            int i = 0;
            for (Queue<Map<String,Object>> queue : processingQueues) {
                final int queueNum = i;
                ThreadPool.getInstance().submit(() -> {
                    Map<String,Object> dict;
                    while ((dict = queue.poll()) != null) {
                        int frameNum = (int) dict.get("frame");
                        LOGGER.trace("Thread {}: processing frame {} (latch count: {})",
                                queueNum, frameNum, latch.getCount());
                        BufferedImage image = (BufferedImage) dict.get("image");
                        image = processFrame(image, opList, info);
                        sequence.set(frameNum, image);
                        latch.countDown();
                    }
                    return null;
                });
                i++;
            }

            // Wait for all threads to finish.
            try {
                latch.await(5, TimeUnit.MINUTES); // hopefully not this long...
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        ImageWriter writer = new ImageWriterFactory()
                .newImageWriter((Encode) opList.getFirst(Encode.class));
        if (opList.getFirst(MetadataCopy.class) != null) {
            writer.setMetadata(metadata);
        }
        writer.write(sequence, outputStream);
    }

    private static BufferedImage processFrame(BufferedImage image,
                                              OperationList opList,
                                              Info imageInfo) {
        return doPostProcess(image, null, opList, imageInfo, null);
    }

    private static BufferedImage doPostProcess(BufferedImage image,
                                               Set<ReaderHint> readerHints,
                                               final OperationList opList,
                                               final Info imageInfo,
                                               ReductionFactor reductionFactor) {
        if (reductionFactor == null) {
            reductionFactor = new ReductionFactor();
        }
        if (readerHints == null) {
            readerHints = EnumSet.noneOf(ReaderHint.class);
        }

        image = Java2DUtil.reduceTo8Bits(image);

        final Dimension fullSize = imageInfo.getSize();

        // N.B.: Any Crop or Rotate operations present in the operation list
        // have already been corrected for this orientation, but we also need
        // to account for operation lists that don't include one or both of
        // those.
        final Orientation orientation = imageInfo.getOrientation();

        if (!Orientation.ROTATE_0.equals(orientation) &&
                opList.getFirst(Crop.class) == null) {
            image = Java2DUtil.rotate(image, orientation);
        }

        // Apply the crop operation, if present, and retain a reference
        // to it for subsequent operations to refer to.
        Crop crop = new Crop(0, 0, image.getWidth(), image.getHeight(),
                imageInfo.getOrientation(), imageInfo.getSize());
        for (Operation op : opList) {
            if (op instanceof Crop) {
                crop = (Crop) op;
                if (crop.hasEffect(fullSize, opList) &&
                        !readerHints.contains(ReaderHint.ALREADY_CROPPED)) {
                    image = Java2DUtil.crop(image, crop, reductionFactor,
                            opList.getScaleConstraint());
                }
            }
        }

        // Redactions happen immediately after cropping.
        final Set<Redaction> redactions = opList.stream()
                .filter(op -> op instanceof Redaction)
                .filter(op -> op.hasEffect(fullSize, opList))
                .map(op -> (Redaction) op)
                .collect(Collectors.toSet());
        Java2DUtil.applyRedactions(image, fullSize, crop,
                new double[] { 1.0, 1.0 }, reductionFactor,
                opList.getScaleConstraint(), redactions);

        if (!Orientation.ROTATE_0.equals(orientation) &&
                opList.getFirst(Crop.class) != null &&
                opList.getFirst(Rotate.class) == null) {
            image = Java2DUtil.rotate(image, orientation);
        }

        // Apply remaining operations.
        for (Operation op : opList) {
            if (op.hasEffect(fullSize, opList)) {
                if (op instanceof Scale) {
                    image = Java2DUtil.scale(image, (Scale) op,
                            opList.getScaleConstraint(), reductionFactor);
                } else if (op instanceof Transpose) {
                    image = Java2DUtil.transpose(image, (Transpose) op);
                } else if (op instanceof Rotate) {
                    image = Java2DUtil.rotate(image, (Rotate) op);
                } else if (op instanceof ColorTransform) {
                    image = Java2DUtil.transformColor(image, (ColorTransform) op);
                } else if (op instanceof Sharpen) {
                    image = Java2DUtil.sharpen(image, (Sharpen) op);
                } else if (op instanceof Overlay) {
                    Java2DUtil.applyOverlay(image, (Overlay) op);
                }
            }
        }

        return image;
    }

    private Java2DPostProcessor() {}

}
