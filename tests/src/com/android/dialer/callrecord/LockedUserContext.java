package com.android.dialer.callrecord;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.UserManager;
import java.io.File;

final class LockedUserContext {

  private LockedUserContext() {}

  static Context wrap(Context baseContext) {
    UserManager userManager = mock(UserManager.class);
    when(userManager.isUserUnlocked()).thenReturn(false);
    return new ContextWrapper(baseContext) {
      @Override
      public Context getApplicationContext() {
        return this;
      }

      @Override
      public Object getSystemService(String name) {
        if (Context.USER_SERVICE.equals(name)) {
          return userManager;
        }
        return super.getSystemService(name);
      }

      @Override
      public File getFilesDir() {
        throw new AssertionError("DataStore should not be opened before unlock");
      }

      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        throw new AssertionError("legacy SharedPreferences should not be opened before unlock");
      }
    };
  }
}
