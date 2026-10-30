package com.pmart.product.content.infrastructure;

import com.pmart.common.web.PmartException;
import com.pmart.product.content.application.ImageStorage;
import java.util.NoSuchElementException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 이미지 본체를 S3 호환 저장소(SeaweedFS 등)의 비공개 버킷 하나에 둔다. 저장소 장애는 DEPENDENCY_UNAVAILABLE. */
public class S3ImageStorage implements ImageStorage {

  private final S3Client client;
  private final String bucket;

  public S3ImageStorage(S3Client client, String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  @Override
  public String bucket() {
    return bucket;
  }

  @Override
  public boolean exists(String key) {
    try {
      client.headObject(b -> b.bucket(bucket).key(key));
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw unavailable(e);
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      client.putObject(
          b ->
              b.bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length),
          RequestBody.fromBytes(bytes));
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  @Override
  public Stored open(String fromBucket, String key) {
    try {
      ResponseInputStream<GetObjectResponse> in =
          client.getObject(b -> b.bucket(fromBucket).key(key));
      Long len = in.response().contentLength();
      return new Stored(in, len == null ? -1 : len);
    } catch (NoSuchKeyException e) {
      throw new NoSuchElementException(key);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        throw new NoSuchElementException(key);
      }
      throw unavailable(e);
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  /** 버킷 확인. 없으면 허용된 경우(로컬·시험)에만 만들고, 아니면 기동을 멈춘다. */
  void ensureBucket(boolean create) {
    try {
      client.headBucket(b -> b.bucket(bucket));
    } catch (S3Exception e) {
      if (e.statusCode() != 404 || !create) {
        throw new IllegalStateException(
            "이미지 버킷 '" + bucket + "' 을 쓸 수 없습니다(HTTP " + e.statusCode() + ")", e);
      }
      client.createBucket(b -> b.bucket(bucket));
    }
  }

  private static PmartException unavailable(Exception e) {
    return PmartException.dependencyUnavailable("이미지 저장소에 연결할 수 없습니다.", e);
  }
}
