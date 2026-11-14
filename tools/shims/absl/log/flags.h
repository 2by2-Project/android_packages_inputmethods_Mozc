//
// Copyright (C) 2026 The 2by2 Project
//
// SPDX-License-Identifier: Apache-2.0
//
// Minimal replacement of absl/log/flags.h. AOSP's external/abseil-cpp does
// not provide the absl/log:flags library; Mozc only relies on the --v flag.
//

#pragma once

#include "absl/flags/declare.h"

ABSL_DECLARE_FLAG(int, v);
