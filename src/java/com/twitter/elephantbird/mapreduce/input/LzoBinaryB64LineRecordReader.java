package com.twitter.elephantbird.mapreduce.input;

import java.io.IOException;
import java.io.InputStream;

import com.twitter.elephantbird.mapreduce.io.BinaryConverter;
import com.twitter.elephantbird.mapreduce.io.BinaryWritable;
import com.twitter.elephantbird.util.Codecs;
import com.twitter.elephantbird.util.HadoopUtils;
import com.twitter.elephantbird.util.TypeRef;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.LineReader;

/**
 * Reads line from an lzo compressed text file, base64 decodes it, and then
 * deserializes that into the templatized object.  Returns <position, protobuf>
 * pairs.
 */
public class  LzoBinaryB64LineRecordReader<M, W extends BinaryWritable<M>> extends LzoRecordReader<LongWritable, W> {

  private LineReader lineReader_;

  private final Text line_ = new Text();
  private final LongWritable key_ = new LongWritable();
  private final W value_;
  private TypeRef<M> typeRef_;

  private final Base64 base64_ = Codecs.createStandardBase64();
  private final BinaryConverter<M> converter_;

  private Counter linesReadCounter;
  private Counter emptyLinesCounter;
  private Counter recordsReadCounter;
  private Counter recordErrorsCounter;

  protected LzoBinaryB64LineRecordReader(TypeRef<M> typeRef, W protobufWritable, BinaryConverter<M> protoConverter) {
    typeRef_ = typeRef;
    converter_ = protoConverter;
    value_ = protobufWritable;
  }

  @Override
  public synchronized void close() throws IOException {
    if (lineReader_ != null) {
      lineReader_.close();
    }
  }

  @Override
  public LongWritable getCurrentKey() throws IOException, InterruptedException {
    return key_;
  }

  @Override
  public W getCurrentValue() throws IOException, InterruptedException {
    return value_;
  }

  @Override
  protected void createInputReader(InputStream input, Configuration conf) throws IOException {
    lineReader_ = new LineReader(input, conf);
  }

  @Override
  public void initialize(InputSplit genericSplit, TaskAttemptContext context)
                                      throws IOException, InterruptedException {
    String group = "LzoB64Lines of " + typeRef_.getRawClass().getName();
    linesReadCounter = HadoopUtils.getCounter(context, group, "Lines Read");
    recordsReadCounter = HadoopUtils.getCounter(context, group, "Records Read");
    recordErrorsCounter = HadoopUtils.getCounter(context, group, "Errors");
    emptyLinesCounter = HadoopUtils.getCounter(context, group, "Empty Lines");
    super.initialize(genericSplit, context);
  }

  @Override
  protected void skipToNextSyncPoint(boolean atFirstRecord) throws IOException {
    if (!atFirstRecord) {
      lineReader_.readLine(new Text());
    }
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    // Since the lzop codec reads everything in lzo blocks, we can't stop if pos == end.
    // Instead we wait for the next block to be read in, when pos will be > end.
    while (pos_ <= end_) {
      key_.set(pos_);

      int newSize = lineReader_.readLine(line_);
      if (newSize == 0) {
        return false;
      }
      linesReadCounter.increment(1);
      pos_ = getLzoFilePos();
      if (line_.equals("\n")) {
        emptyLinesCounter.increment(1);
        continue;
      }
      byte[] lineBytes = line_.toString().getBytes("UTF-8");
      M protoValue = converter_.fromBytes(base64_.decode(lineBytes));
      recordsReadCounter.increment(1);

      if (protoValue == null) {
        recordErrorsCounter.increment(1);
        continue;
      }

      value_.set(protoValue);
      return true;
    }

    return false;
  }
}
