// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package cpulimit

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"golang.org/x/xerrors"
)

type CFSController interface {
	// Usage returns the cpuacct.usage value of the cgroup
	Usage() (usage CPUTime, err error)
	// SetQuota sets a new CFS quota on the cgroup
	SetLimit(limit Bandwidth) (changed bool, err error)
	// NrThrottled returns the number of CFS periods the cgroup was throttled
	NrThrottled() (uint64, error)
}

type CgroupV2CFSController string

func (basePath CgroupV2CFSController) Usage() (CPUTime, error) {
	usage, err := basePath.getFlatKeyedValue("usage_usec")
	if err != nil {
		return 0, err
	}

	return CPUTime(time.Duration(usage) * time.Nanosecond), nil
}

func (basePath CgroupV2CFSController) SetLimit(limit Bandwidth) (changed bool, err error) {
	quota, period, err := basePath.readCpuMax()
	if err != nil {
		return false, xerrors.Errorf("failed to read cpu max: %w", err)
	}
	target := limit.Quota(period)
	if quota == target {
		return false, nil
	}

	err = basePath.writeQuota(target)
	if err != nil {
		return false, xerrors.Errorf("cannot set CFS quota of %d (period is %d, parent quota is %d): %w",
			target.Microseconds(), period.Microseconds(), basePath.readParentQuota().Microseconds(), err)
	}

	return true, nil
}

func (basePath CgroupV2CFSController) NrThrottled() (uint64, error) {
	throttled, err := basePath.getFlatKeyedValue("nr_throttled")
	if err != nil {
		return 0, err
	}

	return uint64(throttled), nil
}

func (basePath CgroupV2CFSController) readCpuMax() (time.Duration, time.Duration, error) {
	cpuMaxPath := filepath.Join(string(basePath), "cpu.max")
	cpuMax, err := os.ReadFile(cpuMaxPath)
	if err != nil {
		return 0, 0, xerrors.Errorf("unable to read file %s: %w", cpuMaxPath, err)
	}

	parts := strings.Fields(string(cpuMax))
	if len(parts) < 2 {
		return 0, 0, xerrors.Errorf("content of %s did not have the required parts: %s", cpuMaxPath, parts)
	}

	quota, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, 0, xerrors.Errorf("could not parse quota of %s: %w", quota, err)
	}

	period, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return 0, 0, xerrors.Errorf("could not parse period of %s: %w", period, err)
	}

	return time.Duration(quota) * time.Microsecond, time.Duration(period) * time.Microsecond, nil
}

func (basePath CgroupV2CFSController) writeQuota(quota time.Duration) error {
	cpuMaxPath := filepath.Join(string(basePath), "cpu.max")
	return os.WriteFile(cpuMaxPath, []byte(strconv.FormatInt(quota.Microseconds(), 10)), 0644)
}

func (basePath CgroupV2CFSController) readParentQuota() time.Duration {
	parent := CgroupV2CFSController(filepath.Dir(string(basePath)))
	quota, _, err := parent.readCpuMax()
	if err != nil {
		return time.Duration(0)
	}

	return time.Duration(quota) * time.Microsecond
}

func (basePath CgroupV2CFSController) getFlatKeyedValue(key string) (int64, error) {
	statsPath := filepath.Join(string(basePath), "cpu.stat")
	stats, err := os.ReadFile(statsPath)
	if err != nil {
		xerrors.Errorf("unable to read stats from %s: %w", stats, err)
	}

	entries := strings.Split(string(stats), "\n")
	for _, entry := range entries {
		if !strings.HasPrefix(entry, key) {
			continue
		}

		kv := strings.Fields(entry)
		if len(kv) < 2 {
			return 0, xerrors.Errorf("cpu usage entry has invalid format: %s", entry)
		}

		value, err := strconv.ParseInt(kv[1], 10, 64)
		if err != nil {
			return 0, xerrors.Errorf("could not parse %s: %w", kv[1], err)
		}

		return value, nil
	}

	return 0, xerrors.Errorf("cpu.stat did not contain %s", key)
}
