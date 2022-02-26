/*
 * Copyright (c) 2021, Huawei Technologies Co. Ltd. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_GC_G1_G1EVACFAILUREREGIONS_HPP
#define SHARE_GC_G1_G1EVACFAILUREREGIONS_HPP

#include "runtime/atomic.hpp"
#include "utilities/bitMap.hpp"

class G1HeapRegionChunkClosure;
class G1ScanChunksInHeapRegions;
class HeapRegionClosure;
class HeapRegionClaimer;

// This class records for every region on the heap whether evacuation failed for it,
// and records for every evacuation failure region to speed up iteration of these
// regions in post evacuation phase.
class G1EvacFailureRegions {
  // Records for every region on the heap whether evacuation failed for it.
  CHeapBitMap _regions_failed_evacuation;
  // Regions (index) of evacuation failed in the current collection.
  uint* _evac_failure_regions;
  // Scans chunks in evacuation failure regions
  G1ScanChunksInHeapRegions* _chunks_in_regions;
  // Number of regions evacuation failed in the current collection.
  volatile uint _evac_failure_regions_cur_length;
  // Maximum of regions number.
  uint _max_regions;
  G1CollectedHeap* _heap;
  G1GCPhaseTimes* _phase_times;

public:
  G1EvacFailureRegions();
  ~G1EvacFailureRegions();

  // Sets up the bitmap and failed regions array for addition.
  void pre_collection(uint max_regions);
  // Drops memory for internal data structures, but keep counts.
  void post_collection();

  bool contains(uint region_idx) const;
  void par_iterate(HeapRegionClosure* closure,
                   HeapRegionClaimer* hrclaimer,
                   uint worker_id) const;
  void initialize_chunks(uint active_workers);
  // Iterate through all chunks in regions that failed evacuation during the entire collection.
  void par_iterate_chunks_in_regions(G1HeapRegionChunkClosure* chunk_closure,
                                     uint worker_id) const;

  // Do necessary preparation for evacuation failure regions
  void prepare_regions();

  uint max_regions() const {
    return _max_regions;
  }

  uint num_regions_failed_evacuation() const {
    return Atomic::load(&_evac_failure_regions_cur_length);
  }

  bool evacuation_failed() const {
    return num_regions_failed_evacuation() > 0;
  }

  // Record that the garbage collection encountered an evacuation failure in the
  // given region. Returns whether this has been the first occurrence of an evacuation
  // failure in that region.
  inline bool record(uint region_idx);
};

#endif //SHARE_GC_G1_G1EVACFAILUREREGIONS_HPP
