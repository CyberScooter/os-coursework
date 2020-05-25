package main.java.process_scheduler.threads;

import main.java.process_scheduler.threads.templates.*;
import main.java.process_scheduler.threads.templates.PCB;
import main.java.views.Controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

public class CPU extends Thread {
    private PCB pcb;

    private static int processCounter;

    //stores number of ios that are still being handled for a specific process ID
    //<ProcessID, NumberOfIOsStillBeingHandled>
    private static ConcurrentHashMap<Integer, CountDownLatch> ioHandlerTracker;

    //this vectorlist stores all the process results for every processID
    //this vector list means that after data has been processed by CPU it stores into this array
    private static Vector<Output> cpuResults;

    public static LinkedHashMap<Integer, LinkedList<String> > cpuResultsCompiled;

    private Semaphore fileCompilingSemaphore = new Semaphore(1);
    private Semaphore terminalSemaphore = new Semaphore(1);
    private Semaphore fileReadingSemaphore = new Semaphore(1);
    //tracks io in progress for specific id

    private Vector<Thread> ioProcesses = new Vector<>();

    private static ConcurrentHashMap<String, String> textFileOutput;

    //used to store result of command from terminal to be tested in junit test
    public static String junitTestOutput;

    public CPU(PCB pcb) {
        this.pcb = pcb;
        processCounter++;
        if(cpuResults == null && ioHandlerTracker == null && textFileOutput == null && cpuResultsCompiled == null){
            cpuResults = new Vector<>();
            textFileOutput = new ConcurrentHashMap<>();
            ioHandlerTracker = new ConcurrentHashMap<>();
            cpuResultsCompiled = new LinkedHashMap<>();
        }
    }

    @Override
    public void run() {
        pcb.setState(PCB.State.running);

        if(pcb.getType() == PCB.Type.fileCompiling){
            try {
                fileCompilingSemaphore.acquire();
                    //if the current process belongs/came from io queue -> ready queue -> this cpu thread
                    //then dont run code below
                    if(pcb.getIOOutput() == null){

                        String[] data = textFileOutput.get(pcb.getFile().getName()).split("\n");

                        for(int x = 0; x < data.length; x++){
                            if (Pattern.matches(RegexExpressions.INTEGER_VARIABLE_REGEX, data[x])) {
                                int indexAtEquals = data[x].indexOf("=");
                                String variableName = data[x].substring(0, indexAtEquals).trim();
                                int value = Integer.parseInt(data[x].substring(indexAtEquals + 1, data[x].length() - 1).trim());
                                cpuResults.add(new Output(pcb.getId(), x+1, variableName, value));

                            } else if (data[x].length() >= 5 && data[x].trim().substring(0,5).equals("print")) {
                                Thread ioProcess = new IO(pcb, data[x], x+1);
                                ioProcesses.add(ioProcess);

                            } else if(Pattern.matches(RegexExpressions.CALCULATION_REGEX1, data[x])) {
                                int index = data[x].indexOf("=");
                                String calculation = data[x].substring(index + 1, data[x].length()-1).trim();
                                if(CodeCompiler.checkCalculationSyntax(calculation)){
                                    cpuResults.add(new Output(pcb.getId(), x+1, data[x].substring(0, index).trim(), calculation, Output.Type.addition));
                                }
                            } else if(Pattern.matches(RegexExpressions.EXIT_REGEX, data[x])){
                                Output exit = new Output(pcb.getId(), true);
                                exit.setLine(x+1);
                                cpuResults.add(exit);
                            } else{
                                Output err = new Output(pcb.getId(), true, "Syntax error at line: " + x+1);
                                err.setLine(x+1);
                                cpuResults.add(err);
                                break;
                            }
                        }

                        if(ioProcesses.size() > 0){
                            //executes IOThreads which handles the IO and sends it back to readyqueue in the ProcessCreation thread
                            executeIOProcesses();
                        }

                        //executes once the io processes are done, as io processes they will use different threads
                        if(cpuResults.size() > 0){
                            CodeCompiler codeCompiler = new CodeCompiler();
                            Vector<Output> cpuResultsForGivenId = new Vector<>();
                            try{
                                Thread.sleep(100);
                            }catch (InterruptedException e){

                            }

                            for(Output output : cpuResults){
                                if(output.getProcessID() == pcb.getId()){
                                    cpuResultsForGivenId.add(output);
                                }
                            }

                            Collections.sort(cpuResultsForGivenId);

                            codeCompiler.compile(cpuResultsForGivenId, CodeCompiler.Type.arithmetic);

                            cpuResultsCompiled.put(pcb.getId(), codeCompiler.getCodeResults());
                            Controller.fileCompiling.countDown();

                        }

                    }else{
                        addIOToResultsList(pcb, ioHandlerTracker);
                    }
            }catch (InterruptedException e){
                System.out.println(e);
            } finally{
                pcb.setState(PCB.State.terminated);
                fileCompilingSemaphore.release();
            }

        }else if(pcb.getType() == PCB.Type.commandLine){
            try {
                terminalSemaphore.acquire();
                if(pcb.getTerminalCode() != null) {
                    boolean cdProcess = pcb.getTerminalCode().checkIfCDCommand();

                    if (cdProcess) {
                        pcb.getTerminalCode().outputResult();
                        Terminal.terminalLatch.countDown();
                    } else {
                        Thread thread = new IO(pcb, pcb.getTerminalCode());
                        thread.start();
                        thread.join();
                    }

                }else if (pcb.isHandledByIO()) {
                    Terminal.commandResult = pcb.getIOOutput().getOutput();
                    //This is used to store output data for terminal code so that it can be
                    //tested in junit test class
                    junitTestOutput = pcb.getIOOutput().getOutput();
                    Terminal.terminalLatch.countDown();
                }
            }catch (InterruptedException e){
                System.out.println(e) ;
            }finally {
                pcb.setState(PCB.State.terminated);
                terminalSemaphore.release();
            }

        }else if(pcb.getType() == PCB.Type.fileReading){
            try {
                fileReadingSemaphore.acquire();
                if (pcb.getIOOutput() == null) {
                    try {
                        Thread IO = new IO(pcb);
                        IO.start();
                        IO.join();
                    } catch (InterruptedException e) {
                        System.out.println(e);
                    }
                } else {
                    textFileOutput.put(pcb.getIOOutput().getFilename(), pcb.getIOOutput().getOutput());
                    Controller.fileReading.countDown();
                }
            }catch (InterruptedException e){
                System.out.println(e);
            }finally {
                pcb.setState(PCB.State.terminated);
                fileReadingSemaphore.release();

            }

        }else if(pcb.getType() == PCB.Type.fileWriting){
            try {
                fileReadingSemaphore.acquire();
                if(pcb.getIOOutput() == null) {
                    try {
                        Thread IO = new IO(pcb, pcb.toWrite());
                        IO.start();
                        IO.join();
                        Controller.fileWriting.countDown();
                    } catch (InterruptedException e) {
                        System.out.println(e);
                    }
                }else{
                    saveToTextFileOutput(pcb.getIOOutput().getFilename(), pcb.getIOOutput().getOutput());
                }

            }catch (InterruptedException e){
                System.out.println(e);
            }finally {
                pcb.setState(PCB.State.terminated);
                fileReadingSemaphore.release();
            }
        }

    }

    private void executeIOProcesses() throws InterruptedException{
        CountDownLatch latch = new CountDownLatch(ioProcesses.size());
        ioHandlerTracker.put(pcb.getId(), latch);
        for(Thread thread : ioProcesses){
            //synchnorise threads so they dont use same resource at the same time
            thread.start();
            thread.join();
        }
        //waits for all io processes to be completed
        ioHandlerTracker.get(pcb.getId()).await();
    }

    private void addIOToResultsList(PCB PCB, ConcurrentHashMap<Integer, CountDownLatch> latch){
        Output output = new Output(PCB.getId(), PCB.getIOOutput());
        output.setLine(PCB.getLineNumber());
        cpuResults.add(output);

        latch.get(PCB.getId()).countDown();
    }

    public static ConcurrentHashMap<String, String> getTextFileOutput() {
        return textFileOutput;
    }

    public static void saveToTextFileOutput(String filename, String data) {
        textFileOutput.put(filename, data);
    }

}
