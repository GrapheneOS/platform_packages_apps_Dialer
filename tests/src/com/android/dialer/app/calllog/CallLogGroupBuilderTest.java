package com.android.dialer.app.calllog;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.phonenumberutil.PhoneNumberHelper;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Call log entries can have a null number (the column is nullable). Grouping one next to an entry
 * whose number contains '#' used to throw a NullPointerException and crash the call log.
 */
@RunWith(AndroidJUnit4.class)
public final class CallLogGroupBuilderTest {

  private static final CallLogGroupBuilder.GroupCreator NO_OP_GROUP_CREATOR =
      new CallLogGroupBuilder.GroupCreator() {
        @Override
        public void addGroup(int cursorPosition, int size) {}

        @Override
        public void setDayGroup(long rowId, int dayGroup) {}

        @Override
        public void clearDayGroups() {}

        @Override
        public void setCallbackAction(long rowId, int callbackAction) {}
      };

  private final CallLogGroupBuilder builder =
      new CallLogGroupBuilder(ApplicationProvider.getApplicationContext(), NO_OP_GROUP_CREATOR);

  @Test
  public void equalNumbers_nullAndNumberWithHash_inEitherOrder_areDifferent() {
    assertThat(builder.equalNumbers(null, "#225")).isFalse();
    assertThat(builder.equalNumbers("#225", null)).isFalse();
  }

  @Test
  public void equalNumbers_sameNumberWithHash_areEqual() {
    assertThat(builder.equalNumbers("*#06#", "*#06#")).isTrue();
    assertThat(builder.equalNumbers("#225", "#2-2-5")).isTrue();
  }

  @Test
  public void equalNumbers_numbersWithHashDifferingOnlyByHash_areDifferent() {
    assertThat(builder.equalNumbers("#123", "123")).isFalse();
  }

  @Test
  public void sameRawNumbers_handlesNull() {
    assertThat(PhoneNumberHelper.sameRawNumbers(null, "#225")).isFalse();
    assertThat(PhoneNumberHelper.sameRawNumbers("#225", null)).isFalse();
    assertThat(PhoneNumberHelper.sameRawNumbers(null, null)).isTrue();
  }
}
