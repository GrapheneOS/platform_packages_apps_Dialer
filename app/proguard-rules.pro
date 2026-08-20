# protobuf-lite binds generated message fields reflectively: the field-name strings are
# baked into RawMessageInfo and resolved with Class#getDeclaredField, so renaming the
# fields makes every message throw at runtime. protobuf-javalite ships no consumer rules;
# this is its own canonical rule, from java/lite/proguard.pgcfg upstream.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
