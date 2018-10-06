/*
 *  Copyright (c) 2018-present, Facebook, Inc.
 *
 *  This source code is licensed under the MIT license found in the LICENSE
 *  file in the root directory of this source tree.
 *
 */

package com.facebook.flipper.plugins.sharedpreferences;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import com.facebook.flipper.core.FlipperConnection;
import com.facebook.flipper.core.FlipperObject;
import com.facebook.flipper.core.FlipperPlugin;
import com.facebook.flipper.core.FlipperReceiver;
import com.facebook.flipper.core.FlipperResponder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SharedPreferencesFlipperPlugin implements FlipperPlugin {

  private FlipperConnection mConnection;
  private final Map<SharedPreferences, SharedPreferencesDescriptor> mSharedPreferences;
  private final SharedPreferences.OnSharedPreferenceChangeListener
      onSharedPreferenceChangeListener =
          new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
              if (mConnection == null) {
                return;
              }
              SharedPreferencesDescriptor descriptor = mSharedPreferences.get(sharedPreferences);
              if (descriptor == null) {
                return;
              }
              mConnection.send(
                  "sharedPreferencesChange",
                  new FlipperObject.Builder()
                      .put("preferences", descriptor.name)
                      .put("name", key)
                      .put("deleted", !sharedPreferences.contains(key))
                      .put("time", System.currentTimeMillis())
                      .put("value", sharedPreferences.getAll().get(key))
                      .build());
            }
          };

  /**
   * Creates a {@link android.content.SharedPreferences} plugin for Flipper
   *
   * @param context The context to retrieve the file from. Will use the package name as the file
   *     name with {@link Context#MODE_PRIVATE}.
   */
  public SharedPreferencesFlipperPlugin(Context context) {
    this(context, context.getPackageName());
  }

  /**
   * Creates a {@link android.content.SharedPreferences} plugin for Flipper
   *
   * @param context The context to retrieve the file from. Will use the name as the file name with
   *     {@link Context#MODE_PRIVATE}.
   * @param name The preference file name.
   */
  public SharedPreferencesFlipperPlugin(Context context, String name) {
    this(context, name, MODE_PRIVATE);
  }

  /**
   * Creates a {@link android.content.SharedPreferences} plugin for Flipper
   *
   * @param context The context to retrieve the file from.
   * @param name The preference file name.
   * @param mode The Context mode to utilize.
   */
  public SharedPreferencesFlipperPlugin(Context context, String name, int mode) {
    this(context, Arrays.asList(new SharedPreferencesDescriptor(name, mode)));
  }

  /**
   * Creates a {@link android.content.SharedPreferences} plugin for Flipper
   *
   * @param context The context to retrieve the preferences from.
   * @param descriptors A list of {@link SharedPreferencesDescriptor}s
   *    that describe the list of preferences to retrieve.
   */
  public SharedPreferencesFlipperPlugin(Context context, List<SharedPreferencesDescriptor> descriptors) {
    if (context == null) {
      throw new IllegalArgumentException("Given null context");
    }
    mSharedPreferences = new HashMap<>(descriptors.size());
    for (SharedPreferencesDescriptor descriptor : descriptors) {
      SharedPreferences preferences = context.getSharedPreferences(descriptor.name, descriptor.mode);
      preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
      mSharedPreferences.put(preferences, descriptor);
    }
  }

  @Override
  public String getId() {
    return "Preferences";
  }

  private SharedPreferences getSharedPreferencesFor(String name) {
    for (Map.Entry<SharedPreferences, SharedPreferencesDescriptor> entry : mSharedPreferences.entrySet()) {
      if (entry.getValue().name.equals(name)) {
        return entry.getKey();
      }
    }
    throw new IllegalStateException("Unknown shared preferences " +name);
  }

  private FlipperObject getFlipperObjectFor(String name) {
    return getFlipperObjectFor(getSharedPreferencesFor(name));
  }

  private FlipperObject getFlipperObjectFor(SharedPreferences sharedPreferences) {
    FlipperObject.Builder builder = new FlipperObject.Builder();
    Map<String, ?> map = sharedPreferences.getAll();
    for (Map.Entry<String, ?> entry : map.entrySet()) {
      final Object val = entry.getValue();
      builder.put(entry.getKey(), val);
    }
    return builder.build();
  }

  @Override
  public void onConnect(FlipperConnection connection) {
    mConnection = connection;

    connection.receive(
        "getAllSharedPreferences",
        new FlipperReceiver() {
          @Override
          public void onReceive(FlipperObject params, FlipperResponder responder) {
            FlipperObject.Builder builder = new FlipperObject.Builder();
            for (Map.Entry<SharedPreferences, SharedPreferencesDescriptor> entry : mSharedPreferences.entrySet()) {
              builder.put(entry.getValue().name, getFlipperObjectFor(entry.getKey()));
            }
            responder.success(builder.build());
          }
        });

    connection.receive(
        "getSharedPreferences",
        new FlipperReceiver() {
          @Override
          public void onReceive(FlipperObject params, FlipperResponder responder) {
            String name = params.getString("name");
            if (name != null) {
              responder.success(getFlipperObjectFor(name));
            }
          }
        });

    connection.receive(
        "setSharedPreference",
        new FlipperReceiver() {
          @Override
          public void onReceive(FlipperObject params, FlipperResponder responder)
              throws IllegalArgumentException {
            String sharedPreferencesName = params.getString("sharedPreferencesName");
            String preferenceName = params.getString("preferenceName");
            SharedPreferences sharedPrefs = getSharedPreferencesFor(sharedPreferencesName);
            Object originalValue = sharedPrefs.getAll().get(preferenceName);
            SharedPreferences.Editor editor = sharedPrefs.edit();

            if (originalValue instanceof Boolean) {
              editor.putBoolean(preferenceName, params.getBoolean("preferenceValue"));
            } else if (originalValue instanceof Long) {
              editor.putLong(preferenceName, params.getLong("preferenceValue"));
            } else if (originalValue instanceof Integer) {
              editor.putInt(preferenceName, params.getInt("preferenceValue"));
            } else if (originalValue instanceof Float) {
              editor.putFloat(preferenceName, params.getFloat("preferenceValue"));
            } else if (originalValue instanceof String) {
              editor.putString(preferenceName, params.getString("preferenceValue"));
            } else {
              throw new IllegalArgumentException("Type not supported: " + preferenceName);
            }

            editor.apply();

            responder.success(getFlipperObjectFor(sharedPreferencesName));
          }
        });
  }

  @Override
  public void onDisconnect() {
    mConnection = null;
  }

  public static class SharedPreferencesDescriptor {
    public final String name;
    public final int mode;

    public SharedPreferencesDescriptor(String name, int mode) {
      if (name == null || name.length() == 0) {
        throw new IllegalArgumentException("Given null or empty name");
      }
      this.name = name;
      this.mode = mode;
    }
  }
}
