package main.java.process_scheduler.threads;

import main.java.Kernel;
import main.java.process_scheduler.threads.templates.Process;
import java.util.concurrent.ConcurrentLinkedQueue;

//keep thread running and will read processes from OSKernal class
public class ProcessCreation extends Thread{
    static ConcurrentLinkedQueue<Process> readyQueue;

    public ProcessCreation() {
        if(readyQueue == null){
            readyQueue = new ConcurrentLinkedQueue<>();
        }
    }

    @Override
    public synchronized void run() {
        //keeps thread running
        while(true) {
            //this uses FCFS to manage for io processes in ready queue to CPU that came from the
            //io queue in the io thread

            //keep the thread running

            Process processToAdd = Kernel.getProcess();

            if(processToAdd != null) {
                processToAdd.setState(Process.State.ready);
                readyQueue.add(processToAdd);
                Kernel.processCreationLatch.countDown();

                if (processToAdd.getIOOutput() != null || processToAdd.getType() == Process.Type.commandLine) {
                    Kernel.dispatcher.interrupt();
                    //two stop two interrupts being called simultaeneously
                    try{
                        Thread.sleep(200);
                    }catch (InterruptedException e){

                    }
                }

            }

            //stops thread every 200ms
            try{
                Thread.sleep(200);
            }catch (InterruptedException e){

            }

        }

    }
    public static ConcurrentLinkedQueue<Process> getReadyQueue() {
        return readyQueue;
    }

}
