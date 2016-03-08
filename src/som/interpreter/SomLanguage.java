package som.interpreter;

import java.io.IOException;

import som.vm.NotYetImplementedException;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrument.Visualizer;
import com.oracle.truffle.api.instrument.WrapperNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

@TruffleLanguage.Registration(name = "SOM", version = "0.1.0", mimeType = SomLanguage.MIME_TYPE)
public class SomLanguage extends TruffleLanguage<Object> {

  public static final String MIME_TYPE = "application/x-mate-som";

  public static final SomLanguage INSTANCE = new SomLanguage();

  @Override
  protected Object createContext(final Env env) {
    throw new NotYetImplementedException();
  }

  @Override
  protected CallTarget parse(final Source code, final Node context,
      final String... argumentNames) throws IOException {
    throw new NotYetImplementedException();
  }

  @Override
  protected Object findExportedSymbol(final Object context,
      final String globalName, final boolean onlyExplicit) {
    throw new NotYetImplementedException();
  }

  @Override
  protected Object getLanguageGlobal(final Object context) {
    throw new NotYetImplementedException();
  }

  @Override
  protected boolean isObjectOfLanguage(final Object object) {
    throw new NotYetImplementedException();
  }

  @Override
  protected Visualizer getVisualizer() {
    throw new NotYetImplementedException();
  }

  @Override
  protected boolean isInstrumentable(final Node node) {
    throw new NotYetImplementedException();
  }

  @Override
  protected WrapperNode createWrapperNode(final Node node) {
    throw new NotYetImplementedException();
  }

  @Override
  protected Object evalInContext(final Source source, final Node node,
      final MaterializedFrame mFrame) throws IOException {
    throw new NotYetImplementedException();
  }
}
