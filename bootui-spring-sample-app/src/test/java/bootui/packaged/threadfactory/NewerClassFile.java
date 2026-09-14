package bootui.packaged.threadfactory;

import java.util.concurrent.ThreadFactory;

public class NewerClassFile {
    ThreadFactory factory() {
        return task -> new Thread(task);
    }
}
