/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.modules.fresco;

import java.util.HashSet;

import android.content.Context;
import android.support.annotation.Nullable;
import android.util.SparseIntArray;

import com.facebook.common.logging.FLog;
import com.facebook.common.util.ByteConstants;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.backends.okhttp3.OkHttpImagePipelineConfigFactory;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.listener.RequestListener;
import com.facebook.imagepipeline.memory.PoolConfig;
import com.facebook.imagepipeline.memory.PoolFactory;
import com.facebook.imagepipeline.memory.PoolParams;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.common.ModuleDataCleaner;
import com.facebook.react.modules.network.CookieJarContainer;
import com.facebook.react.modules.network.ForwardingCookieHandler;
import com.facebook.react.modules.network.OkHttpClientProvider;
import com.facebook.soloader.SoLoader;

import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

/**
 * Module to initialize the Fresco library.
 *
 * <p>Does not expose any methods to JavaScript code. For initialization and cleanup only.
 */
@ReactModule(name = "FrescoModule")
public class FrescoModule extends ReactContextBaseJavaModule implements
    ModuleDataCleaner.Cleanable, LifecycleEventListener {

  private final boolean mClearOnDestroy;
  private @Nullable ImagePipelineConfig mConfig;

  private static boolean sHasBeenInitialized = false;

  /**
   * Create a new Fresco module with a default configuration (or the previously given
   * configuration via {@link #FrescoModule(ReactApplicationContext, boolean, ImagePipelineConfig)}.
   *
   * @param reactContext the context to use
   */
  public FrescoModule(ReactApplicationContext reactContext) {
    this(reactContext, true, null);
  }

  /**
   * Create a new Fresco module with a default configuration (or the previously given
   * configuration via {@link #FrescoModule(ReactApplicationContext, boolean, ImagePipelineConfig)}.
   *
   * @param clearOnDestroy whether to clear the memory cache in onHostDestroy: this should be
   *        {@code true} for pure RN apps and {@code false} for apps that use Fresco outside of RN
   *        as well
   * @param reactContext the context to use
   *
   */
  public FrescoModule(ReactApplicationContext reactContext, boolean clearOnDestroy) {
    this(reactContext, clearOnDestroy, null);
  }

  /**
   * Create a new Fresco module with a given ImagePipelineConfig.
   * This should only be called when the module has not been initialized yet.
   * You can use {@link #hasBeenInitialized()} to check this and call
   * {@link #FrescoModule(ReactApplicationContext)} if it is already initialized.
   * Otherwise, the given Fresco configuration will be ignored.
   *
   * @param reactContext the context to use
   * @param clearOnDestroy whether to clear the memory cache in onHostDestroy: this should be
   *        {@code true} for pure RN apps and {@code false} for apps that use Fresco outside of RN
   *        as well
   * @param config the Fresco configuration, which will only be used for the first initialization
   */
  public FrescoModule(
    ReactApplicationContext reactContext,
    boolean clearOnDestroy,
    @Nullable ImagePipelineConfig config) {
    super(reactContext);
    mClearOnDestroy = clearOnDestroy;
    mConfig = config;
  }

  @Override
  public void initialize() {
    super.initialize();
    getReactApplicationContext().addLifecycleEventListener(this);
    if (!hasBeenInitialized()) {
      if (mConfig == null) {
        mConfig = getDefaultConfig(getReactApplicationContext());
      }
      Context context = getReactApplicationContext().getApplicationContext();
      Fresco.initialize(context, mConfig);
      sHasBeenInitialized = true;
    } else if (mConfig != null) {
      FLog.w(
          ReactConstants.TAG,
          "Fresco has already been initialized with a different config. "
          + "The new Fresco configuration will be ignored!");
    }
    mConfig = null;
  }

  @Override
  public String getName() {
    return "FrescoModule";
  }

  @Override
  public void clearSensitiveData() {
    // Clear image cache.
    Fresco.getImagePipeline().clearCaches();
  }

  /**
   * Check whether the FrescoModule has already been initialized. If this is the case,
   * Calls to {@link #FrescoModule(ReactApplicationContext, ImagePipelineConfig)} will
   * ignore the given configuration.
   *
   * @return true if this module has already been initialized
   */
  public static boolean hasBeenInitialized() {
    return sHasBeenInitialized;
  }

  private static ImagePipelineConfig getDefaultConfig(ReactContext context) {
    return getDefaultConfigBuilder(context).build();
  }

  /**
   * Get the default Fresco configuration builder.
   * Allows adding of configuration options in addition to the default values.
   *
   * @return {@link ImagePipelineConfig.Builder} that has been initialized with default values
   */
  public static ImagePipelineConfig.Builder getDefaultConfigBuilder(ReactContext context) {
    HashSet<RequestListener> requestListeners = new HashSet<>();
    requestListeners.add(new SystraceRequestListener());

    OkHttpClient client = OkHttpClientProvider.createClient();

    // make sure to forward cookies for any requests via the okHttpClient
    // so that image requests to endpoints that use cookies still work
    CookieJarContainer container = (CookieJarContainer) client.cookieJar();
    ForwardingCookieHandler handler = new ForwardingCookieHandler(context);
    container.setCookieJar(new JavaNetCookieJar(handler));

    long maxMem = Runtime.getRuntime().maxMemory();
    return OkHttpImagePipelineConfigFactory
      .newBuilder(context.getApplicationContext(), client)
      .setNetworkFetcher(new ReactOkHttpNetworkFetcher(client))
      .setDownsampleEnabled(false)
      .setPoolFactory(new PoolFactory(PoolConfig.newBuilder()
        .setBitmapPoolParams(new PoolParams(
          0,
          368* ByteConstants.MB,
          new SparseIntArray(0)
        ))
        .build()))
      .setRequestListeners(requestListeners);
  }

  @Override
  public void onHostResume() {
  }

  @Override
  public void onHostPause() {
  }

  @Override
  public void onHostDestroy() {
    // According to the javadoc for LifecycleEventListener#onHostDestroy, this is only called when
    // the 'last' ReactActivity is being destroyed, which effectively means the app is being
    // backgrounded.
    if (hasBeenInitialized() && mClearOnDestroy) {
      Fresco.getImagePipeline().clearMemoryCaches();
    }
  }
}
