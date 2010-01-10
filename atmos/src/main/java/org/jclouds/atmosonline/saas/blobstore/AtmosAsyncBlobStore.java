/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.atmosonline.saas.blobstore;

import static com.google.common.util.concurrent.Futures.compose;
import static com.google.common.util.concurrent.Futures.makeListenable;
import static org.jclouds.blobstore.options.ListContainerOptions.Builder.recursive;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;

import org.jclouds.atmosonline.saas.AtmosStorageAsyncClient;
import org.jclouds.atmosonline.saas.AtmosStorageClient;
import org.jclouds.atmosonline.saas.blobstore.functions.BlobStoreListOptionsToListOptions;
import org.jclouds.atmosonline.saas.blobstore.functions.BlobToObject;
import org.jclouds.atmosonline.saas.blobstore.functions.DirectoryEntryListToResourceMetadataList;
import org.jclouds.atmosonline.saas.blobstore.functions.ObjectToBlob;
import org.jclouds.atmosonline.saas.blobstore.functions.ObjectToBlobMetadata;
import org.jclouds.atmosonline.saas.blobstore.internal.BaseAtmosBlobStore;
import org.jclouds.atmosonline.saas.domain.AtmosObject;
import org.jclouds.atmosonline.saas.options.ListOptions;
import org.jclouds.blobstore.AsyncBlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.ListContainerResponse;
import org.jclouds.blobstore.domain.ListResponse;
import org.jclouds.blobstore.domain.ResourceMetadata;
import org.jclouds.blobstore.domain.Blob.Factory;
import org.jclouds.blobstore.functions.BlobToHttpGetOptions;
import org.jclouds.blobstore.strategy.ClearListStrategy;
import org.jclouds.encryption.EncryptionService;
import org.jclouds.http.options.GetOptions;
import org.jclouds.logging.Logger.LoggerFactory;
import org.jclouds.util.Utils;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * @author Adrian Cole
 */
public class AtmosAsyncBlobStore extends BaseAtmosBlobStore implements AsyncBlobStore {
   private final EncryptionService encryptionService;

   @Inject
   public AtmosAsyncBlobStore(AtmosStorageAsyncClient async, AtmosStorageClient sync,
            Factory blobFactory, LoggerFactory logFactory,
            ClearListStrategy clearContainerStrategy, ObjectToBlobMetadata object2BlobMd,
            ObjectToBlob object2Blob, BlobToObject blob2Object,
            BlobStoreListOptionsToListOptions container2ContainerListOptions,
            BlobToHttpGetOptions blob2ObjectGetOptions,
            DirectoryEntryListToResourceMetadataList container2ResourceList,
            ExecutorService service, EncryptionService encryptionService) {
      super(async, sync, blobFactory, logFactory, clearContainerStrategy, object2BlobMd,
               object2Blob, blob2Object, container2ContainerListOptions, blob2ObjectGetOptions,
               container2ResourceList, service);
      this.encryptionService = encryptionService;
   }

   /**
    * This implementation uses the AtmosStorage HEAD Object command to return the result
    */
   public ListenableFuture<BlobMetadata> blobMetadata(String container, String key) {
      return compose(async.headFile(container + "/" + key),
               new Function<AtmosObject, BlobMetadata>() {
                  @Override
                  public BlobMetadata apply(AtmosObject from) {
                     return object2BlobMd.apply(from);
                  }
               }, service);
   }

   public ListenableFuture<Void> clearContainer(final String container) {
      return makeListenable(service.submit(new Callable<Void>() {

         public Void call() throws Exception {
            clearContainerStrategy.execute(container, recursive());
            return null;
         }

      }));
   }

   public ListenableFuture<Boolean> createContainer(String container) {
      return compose(async.createDirectory(container), new Function<URI, Boolean>() {

         public Boolean apply(URI from) {
            return true;// no etag
         }

      });
   }

   public ListenableFuture<Void> createDirectory(String container, String directory) {
      return compose(async.createDirectory(container + "/" + directory), new Function<URI, Void>() {

         public Void apply(URI from) {
            return null;// no etag
         }

      });
   }

   public ListenableFuture<Void> deleteContainer(final String container) {
      return makeListenable(service.submit(new Callable<Void>() {

         public Void call() throws Exception {
            clearContainerStrategy.execute(container, recursive());
            async.deletePath(container).get();
            if (!Utils.enventuallyTrue(new Supplier<Boolean>() {
               public Boolean get() {
                  return !sync.pathExists(container);
               }
            }, requestTimeoutMilliseconds)) {
               throw new IllegalStateException(container + " still exists after deleting!");
            }
            return null;
         }

      }));
   }

   public ListenableFuture<Boolean> containerExists(String container) {
      return async.pathExists(container);
   }

   public ListenableFuture<Boolean> directoryExists(String container, String directory) {
      return async.pathExists(container + "/" + directory);
   }

   public ListenableFuture<Blob> getBlob(String container, String key,
            org.jclouds.blobstore.options.GetOptions... optionsList) {
      GetOptions httpOptions = blob2ObjectGetOptions.apply(optionsList);
      ListenableFuture<AtmosObject> returnVal = async.readFile(container + "/" + key, httpOptions);
      return compose(returnVal, object2Blob, service);
   }

   public ListenableFuture<? extends ListResponse<? extends ResourceMetadata>> list() {
      return compose(async.listDirectories(), container2ResourceList, service);
   }

   public ListenableFuture<? extends ListContainerResponse<? extends ResourceMetadata>> list(
            String container, org.jclouds.blobstore.options.ListContainerOptions... optionsList) {
      if (optionsList.length == 1) {
         if (optionsList[0].isRecursive()) {
            throw new UnsupportedOperationException("recursive not currently supported in emcsaas");
         }
         if (optionsList[0].getDir() != null) {
            container = container + "/" + optionsList[0].getDir();
         }
      }
      ListOptions nativeOptions = container2ContainerListOptions.apply(optionsList);
      return compose(async.listDirectory(container, nativeOptions), container2ResourceList, service);
   }

   /**
    * Since there is no etag support in atmos, we just return the path.
    */
   public ListenableFuture<String> putBlob(final String container, final Blob blob) {
      final String path = container + "/" + blob.getMetadata().getName();
      return compose(async.deletePath(path), new Function<Void, String>() {

         public String apply(Void from) {
            try {
               if (!Utils.enventuallyTrue(new Supplier<Boolean>() {
                  public Boolean get() {
                     return !sync.pathExists(path);
                  }
               }, requestTimeoutMilliseconds)) {
                  throw new IllegalStateException(path + " still exists after deleting!");
               }
               if (blob.getMetadata().getContentMD5() != null)
                  blob.getMetadata().getUserMetadata().put("content-md5",
                           encryptionService.toHexString(blob.getMetadata().getContentMD5()));
               sync.createFile(container, blob2Object.apply(blob));
               return path;
            } catch (InterruptedException e) {
               throw new RuntimeException(e);
            }
         }

      }, service);

   }

   public ListenableFuture<Void> removeBlob(String container, String key) {
      return async.deletePath(container + "/" + key);
   }

}
