// Disclaimer: IMPORTANT: This software was developed at the National
// Institute of Standards and Technology by employees of the Federal
// Government in the course of their official duties. Pursuant to
// title 17 Section 105 of the United States Code this software is not
// subject to copyright protection and is in the public domain. This
// is an experimental system. NIST assumes no responsibility
// whatsoever for its use by other parties, and makes no guarantees,
// expressed or implied, about its quality, reliability, or any other
// characteristic. We would appreciate acknowledgement if the software
// is used. This software can be redistributed and/or modified freely
// provided that any derivative works bear some notice that they are
// derived from it, and any modified versions bear some notice that
// they have been modified.


// ================================================================
//
// Author: tjb3
// Date: Oct 1, 2014 1:42:44 PM EST
//
// Time-stamp: <Oct 1, 2014 1:42:44 PM tjb3>
//
// ================================================================

package gov.nist.isg.mist.lib32.executor;

import java.io.FileNotFoundException;
import java.io.InvalidClassException;
import java.util.List;

import javax.swing.*;

import gov.nist.isg.mist.gui.params.StitchingAppParams;
import gov.nist.isg.mist.gui.params.objects.CudaDeviceParam;
import gov.nist.isg.mist.lib.exceptions.EmptyGridException;
import gov.nist.isg.mist.lib.executor.StitchingExecutor;
import gov.nist.isg.mist.lib.executor.StitchingExecutorInterface;
import gov.nist.isg.mist.lib.imagetile.ImageTile;
import gov.nist.isg.mist.lib.imagetile.jcuda.CudaUtils;
import gov.nist.isg.mist.lib.log.Log;
import gov.nist.isg.mist.lib.log.Log.LogType;
import gov.nist.isg.mist.lib.tilegrid.TileGrid;
import gov.nist.isg.mist.lib32.imagetile.jcuda.CudaImageTile32;
import gov.nist.isg.mist.lib32.parallel.gpu.GPUStitchingThreadExecutor32;
import jcuda.CudaException;
import jcuda.Sizeof;
import jcuda.driver.CUcontext;
import jcuda.driver.JCudaDriver;
import jcuda.jcufft.JCufft;
import jcuda.runtime.cudaError;

/**
 * CudaStitchingExecutor executes the stitching using CUDA
 *
 * @author Tim Blattner
 */
public class CudaStitchingExecutor32<T> implements StitchingExecutorInterface<T> {

  private boolean librariesInitialized;
  private boolean init;
  private CUcontext[] contexts;
  private int[] devIDs;
  private StitchingExecutor executor;

  private GPUStitchingThreadExecutor32<T> gpuExecutor;

  public CudaStitchingExecutor32(StitchingExecutor executor) {
    this.librariesInitialized = false;
    this.init = false;
    this.contexts = null;
    this.devIDs = null;
    this.executor = executor;
  }

  @Override
  public void cancelExecution() {
    if (this.gpuExecutor != null)
      this.gpuExecutor.cancel();
  }

  @Override
  public void launchStitching(TileGrid<ImageTile<T>> grid, StitchingAppParams params,
                              JProgressBar progressBar, int timeSlice) throws Throwable {

    ImageTile<T> tile = grid.getTileThatExists();
    tile.readTile();


    this.executor.initProgressBar();


    if (this.contexts == null) {
      Log.msg(LogType.MANDATORY, "Error initializing CUDA");
      throw new CudaException("Error initializing CUDA");
    }

    this.gpuExecutor = new GPUStitchingThreadExecutor32<T>(this.contexts.length,
        params.getAdvancedParams().getNumCPUThreads(), tile, grid, this.contexts, this.devIDs,
        progressBar, this.executor, params.getAdvancedParams().isEnableCudaExceptions());

    tile.releasePixels();

    this.gpuExecutor.execute();

    if (this.gpuExecutor.isExceptionThrown())
      throw this.gpuExecutor.getWorkerThrowable();

  }

  @Override
  public boolean checkForLibs(StitchingAppParams params, boolean displayGui) {

    if (this.librariesInitialized)
      return true;


    try {
      int[] count = new int[1];
      int ret = JCudaDriver.cuDeviceGetCount(count);
      if (ret == cudaError.cudaSuccess && count[0] > 0) {
        this.librariesInitialized = true;
        return true;
      }

      JCufft.setExceptionsEnabled(true);
      JCufft.initialize();
      JCufft.setExceptionsEnabled(params.getAdvancedParams().isEnableCudaExceptions());

      return true;
    } catch (UnsatisfiedLinkError err) {
      Log.msg(LogType.MANDATORY, "Error loading CUDA. Unable to find " + "required libraries: "
          + err.getMessage());
      Log.msg(LogType.MANDATORY, "You must install the cuda toolkit to "
          + "make this version work: nvidia.com/getcuda");
      return false;
    } catch (NullPointerException ex) {
      Log.msg(LogType.MANDATORY, "Error loading CUDA. Unable to find " + "required libraries: "
          + ex.getMessage());
      Log.msg(LogType.MANDATORY, "You must install the cuda toolkit to "
          + "make this version work: nvidia.com/getcuda");
      return false;
    }
  }

  @Override
  public TileGrid<ImageTile<T>> initGrid(StitchingAppParams params, int timeSlice)
      throws FileNotFoundException, EmptyGridException {

    TileGrid<ImageTile<T>> grid = null;

    if (params.getInputParams().isTimeSlicesEnabled()) {
      try {
        grid = new TileGrid<ImageTile<T>>(params, timeSlice, CudaImageTile32.class);
      } catch (InvalidClassException e) {
        e.printStackTrace();
      }
    } else {
      try {
        grid = new TileGrid<ImageTile<T>>(params, CudaImageTile32.class);
      } catch (InvalidClassException e) {
        e.printStackTrace();
      }
    }

    List<CudaDeviceParam> devices = params.getAdvancedParams().getCudaDevices();
    ImageTile<T> tile = grid.getTileThatExists();
    if (tile == null)
      throw new EmptyGridException("Image Tile Grid contains no valid tiles. Check " +
          "Stitching Parameters");
    tile.readTile();


    if (!this.init) {

      if (devices.size() == 0) {
        this.devIDs = new int[]{0};
        Log.msg(LogType.MANDATORY, "No device selected from " + "table. Using default (0)");
        this.contexts = CudaUtils.initJCUDA(1, this.devIDs, tile, params.getAdvancedParams().isEnableCudaExceptions());
      } else {
        Log.msg(LogType.MANDATORY, devices.size() + " device(s) selected from table.");
        this.contexts = CudaUtils.initJCUDA(devices, tile, params.getAdvancedParams().isEnableCudaExceptions());
        this.devIDs = new int[devices.size()];
        for (int j = 0; j < devices.size(); j++)
          this.devIDs[j] = devices.get(j).getId();
      }

      this.init = true;
    }


    return grid;
  }

  @Override
  public void cleanup() {
    if(this.init)
      CudaUtils.destroyJCUDA(this.contexts.length);
  }

  @Override
  public <T> boolean checkMemory(TileGrid<ImageTile<T>> grid, int numWorkers)
      throws FileNotFoundException {

    // Check the CPU side memory
    long requiredCPUMemoryBytes = 0;
    long requiredGPUMemoryBytes = 0;

    long memoryPoolCount = Math.min(grid.getExtentHeight(), grid.getExtentWidth()) + 2L + numWorkers;
    ImageTile<T> tile = grid.getTileThatExists();
    tile.readTile();

    // Account for image pixel data
    if (ImageTile.freePixelData()) {
      // If freeing image pixel data
      // only memorypool size tiles are stored
      requiredCPUMemoryBytes += (long) tile.getHeight() * (long) tile.getWidth() * memoryPoolCount * 2L; // 16 bit pixel data
    } else {
      // If not freeing image pixel data
      // must hold whole image grid in memory
      requiredCPUMemoryBytes += (long) tile.getHeight() * (long) tile.getWidth() * (long) grid.getSubGridSize() * 2L; // 16 bit pixel data
    }

    // Account for image pixel data type conversion
    long byteDepth = tile.getBitDepth() / 2;
    if (byteDepth != 2) {
      // if up-converting at worst case there will be numWorkers copies of the old precision pixel data
      requiredCPUMemoryBytes += (long) numWorkers * (long) tile.getHeight() * (long) tile.getWidth() * byteDepth;
    }


    // Check GPU side memory
    long minGPUMemory = Long.MAX_VALUE;
    for (CUcontext c : contexts) {
      minGPUMemory = Math.min(minGPUMemory, CudaUtils.getFreeCudaMemory(c));
    }

    // from CudaTileWorkerMemory.java
    long perGPUPinnedMemory = 0;
    perGPUPinnedMemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.FLOAT;
    perGPUPinnedMemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.INT;
    perGPUPinnedMemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.INT;
    perGPUPinnedMemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.INT;

    // from CudaTileWorkerMemory.java and TileGPUPciamWorker.java
    long perGPUmemory = 0;
    perGPUmemory += (long) CudaImageTile32.fftSize * 2L * Sizeof.FLOAT;
    perGPUmemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.FLOAT;
    perGPUmemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.FLOAT;
    perGPUmemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.FLOAT;

    perGPUmemory += (long) tile.getHeight() * (long) tile.getWidth() * Sizeof.FLOAT;
    perGPUmemory += (long) CudaImageTile32.fftSize * 2L * Sizeof.FLOAT;


    perGPUmemory += memoryPoolCount * ((long) CudaImageTile32.fftSize * 2L * Sizeof.FLOAT);


    requiredCPUMemoryBytes += perGPUPinnedMemory;
    requiredGPUMemoryBytes += (perGPUmemory + perGPUPinnedMemory);


    // pad with 10MB
    requiredCPUMemoryBytes += 10L * 1024L * 1024L;
    // pad with 10MB
    requiredGPUMemoryBytes += 10L * 1024L * 1024L;

    return (requiredCPUMemoryBytes < Runtime.getRuntime().maxMemory()) && (requiredGPUMemoryBytes < minGPUMemory);
  }

}