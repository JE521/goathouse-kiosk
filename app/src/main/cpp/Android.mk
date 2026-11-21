LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := goatprint
GP := goatprint
LOCAL_SRC_FILES := goatprint_jni.c $(GP)/src/image.c $(GP)/src/match.c \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/$(GP)/third_party/nbis/mindtct/*.c)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/$(GP)/third_party/nbis/bozorth3/*.c))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(GP)/include $(LOCAL_PATH)/$(GP)/third_party/nbis/include
LOCAL_CFLAGS := -std=gnu11 -O2 -w
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
