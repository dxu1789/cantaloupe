package edu.illinois.library.cantaloupe.resolver;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import edu.illinois.library.cantaloupe.Application;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.request.Identifier;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * @see <a href="http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/welcome.html">
 *     AWS SDK for Java</a>
 */
class AmazonS3Resolver implements StreamResolver {

    private static class ConfigFileCredentials implements AWSCredentials {

        @Override
        public String getAWSAccessKeyId() {
            Configuration config = Application.getConfiguration();
            return config.getString(ACCESS_KEY_ID_CONFIG_KEY);
        }

        @Override
        public String getAWSSecretKey() {
            Configuration config = Application.getConfiguration();
            return config.getString(SECRET_KEY_CONFIG_KEY);
        }

    }

    private static Logger logger = LoggerFactory.
            getLogger(AmazonS3Resolver.class);

    public static final String ACCESS_KEY_ID_CONFIG_KEY =
            "AmazonS3Resolver.access_key_id";
    public static final String BUCKET_NAME_CONFIG_KEY =
            "AmazonS3Resolver.bucket.name";
    public static final String BUCKET_REGION_CONFIG_KEY =
            "AmazonS3Resolver.bucket.region";
    public static final String SECRET_KEY_CONFIG_KEY =
            "AmazonS3Resolver.secret_key";

    private static AmazonS3 client;

    private static AmazonS3 getClientInstance() {
        if (client == null) {
            AWSCredentials credentials = new ConfigFileCredentials();
            client = new AmazonS3Client(credentials);
            Configuration config = Application.getConfiguration();
            final String regionName = config.getString(BUCKET_REGION_CONFIG_KEY);
            if (regionName != null) {
                Regions regions = Regions.fromName(regionName);
                Region region = Region.getRegion(regions);
                logger.debug("Using region: {}", region);
                client.setRegion(region);
            }
        }
        return client;
    }

    @Override
    public InputStream getInputStream(Identifier identifier) throws IOException {
        AmazonS3 s3 = getClientInstance();

        Configuration config = Application.getConfiguration();
        final String bucketName = config.getString(BUCKET_NAME_CONFIG_KEY);
        logger.debug("Using bucket: {}", bucketName);
        final String objectKey = identifier.getValue();
        logger.debug("Requesting {}", objectKey);
        try {
            S3Object object = s3.getObject(new GetObjectRequest(bucketName, objectKey));
            return object.getObjectContent();
        } catch (AmazonS3Exception e) {
            if (e.getErrorCode().equals("NoSuchKey")) {
                throw new FileNotFoundException(e.getMessage());
            } else {
                throw new IOException(e);
            }
        }
    }

    @Override
    public SourceFormat getSourceFormat(Identifier identifier) throws IOException {
        return SourceFormat.getSourceFormat(identifier);
    }

}
