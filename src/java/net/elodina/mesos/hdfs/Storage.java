package net.elodina.mesos.hdfs;

import net.elodina.mesos.util.IO;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOError;
import java.io.IOException;

public abstract class Storage {
    public static Storage file(File file) { return new FileStorage(file); }

    public abstract void save();
    public abstract void load();
    public abstract void clear();

    private static class FileStorage extends Storage {
        private File file;
        private FileStorage(File file) { this.file = file; }

        @Override
        public void save() {
            JSONObject json = Nodes.toJson();
            try { IO.writeFile(file, "" + json); }
            catch (IOException e) { throw new IOError(e); }
        }

        @Override
        public void load() {
            if (!file.exists()) return;

            JSONObject obj;
            try { obj = (JSONObject) new JSONParser().parse(IO.readFile(file)); }
            catch (ParseException | IOException e) { throw new IOError(e); }

            Nodes.fromJson(obj);
        }

        @Override
        public void clear() {
            if (!file.exists()) return;
            if (!file.delete()) throw new IOError(new IOException("failed to delete " + file));
        }
    }

}


