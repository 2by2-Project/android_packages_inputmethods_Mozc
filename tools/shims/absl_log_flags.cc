//
// Copyright (C) 2026 The 2by2 Project
//
// SPDX-License-Identifier: Apache-2.0
//

#include "absl/flags/flag.h"
#include "absl/log/flags.h"

ABSL_FLAG(int, v, 0,
          "Show all VLOG(m) messages for m <= this. Overridable by --vmodule.");
