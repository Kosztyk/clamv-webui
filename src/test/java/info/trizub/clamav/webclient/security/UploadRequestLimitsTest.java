package info.trizub.clamav.webclient.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UploadRequestLimitsTest {
  @Test
  void smallSingleFileIsRejectedEarlyWithoutReadingRequestBody() {
    long limit = UploadRequestLimits.ceiling(2_147_483_648L, 1024L, 1);
    assertThat(limit).isLessThan(1024L + 131_073L);
    assertThat(10_000_000L).isGreaterThan(limit);
  }

  @Test
  void multiFileBatchCanExceedSingleFileLimitWithoutFalseRejection() {
    long limit = UploadRequestLimits.ceiling(2_147_483_648L, 1024L, 3);
    assertThat(limit).isGreaterThan(3_000L);
  }

  @Test
  void computedLimitsCannotOverflowOrExceedServletRequestLimit() {
    assertThat(UploadRequestLimits.ceiling(2_147_483_648L, Long.MAX_VALUE, 10000))
        .isEqualTo(2_147_483_648L);
    assertThat(UploadRequestLimits.ceiling(100_000L, 300_000L, 1)).isEqualTo(100_000L);
  }

  @Test
  void invalidCountsAreRejected() {
    assertThatThrownBy(() -> UploadRequestLimits.ceiling(1000, 1000, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
