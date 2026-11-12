package edu.project;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

/** Reproducible batch task-to-VM scheduling experiment; all tasks arrive at t=0. */
public final class SchedulerExperiment {
    static final double A=.50, B=.20, C=.30;
    static final int POP=30, BUDGET=1500;
    static final String[] METHODS={"RoundRobin","MinMin","GA","DiscreteSSA","GA_SSA_reconstruction","DECSO_inspired","EcoTaskOpt_inspired","SA_DGSSA_BR","NoRepair","NoAdapt"};

    record Instance(long[] length, long[] deadline, long[] mips, String workload, long seed) {
        int n(){return length.length;} int m(){return mips.length;}
    }
    record Metrics(double makespan,double tardiness,double missRate,double imbalance,double fitness,double[] finish) {}
    record Run(int[] assignment,Metrics metrics,long evals,double optimizerMs) {}
    static final class Budget {
        final Instance in; final double normalizer; final int limit; int used=0;
        Budget(Instance in,double normalizer,int limit){this.in=in;this.normalizer=normalizer;this.limit=limit;}
        Metrics score(int[] x){ used++;return measure(in,x,normalizer,null); }
        boolean room(){return used<limit;}
    }
    static final class Candidate {
        int[] x; Metrics metrics;
        Candidate(int[] x,Metrics metrics){this.x=x;this.metrics=metrics;}
        Candidate copy(){return new Candidate(x.clone(),metrics);}
    }
    static final Comparator<Candidate> ORDER=Comparator.comparingDouble(z->z.metrics.fitness);

    static Instance generate(int n,int m,String workload,long seed) {
        Random r=new Random(seed); long[] lengths=new long[n], speeds=new long[m], deadlines=new long[n];
        for(int j=0;j<m;j++) speeds[j]=800+r.nextInt(401);
        long total=0;
        for(int i=0;i<n;i++) {
            double seconds;
            if(workload.equals("heavy")) seconds=Math.min(350,10+16*Math.exp(1.05*r.nextGaussian()));
            else seconds=10+r.nextInt(91);
            lengths[i]=Math.max(1000,Math.round(1000*seconds)); total+=lengths[i];
        }
        double ideal=total/(double)Arrays.stream(speeds).sum();
        for(int i=0;i<n;i++) deadlines[i]=Math.max(1,Math.round(ideal*(.25+.90*r.nextDouble())));
        return new Instance(lengths,deadlines,speeds,workload,seed);
    }
    static int[] roundRobin(Instance in){int[] x=new int[in.n()];for(int i=0;i<x.length;i++)x[i]=i%in.m();return x;}
    static int[] minMin(Instance in){
        int[] x=new int[in.n()];Arrays.fill(x,-1);double[] busy=new double[in.m()];
        for(int t=0;t<in.n();t++){
            int bestI=-1,bestJ=-1;double best=Double.POSITIVE_INFINITY;
            for(int i=0;i<in.n();i++)if(x[i]<0){
                int vm=-1;double finish=Double.POSITIVE_INFINITY;
                for(int j=0;j<in.m();j++){double f=busy[j]+in.length[i]/(double)in.mips[j];if(f<finish){finish=f;vm=j;}}
                if(finish<best){best=finish;bestI=i;bestJ=vm;}
            }
            x[bestI]=bestJ;busy[bestJ]+=in.length[bestI]/(double)in.mips[bestJ];
        }
        return x;
    }
    static Metrics measure(Instance in,int[] x,double m0,double[] simulatedFinish){
        if(x.length!=in.n())throw new IllegalArgumentException("mapping length");
        @SuppressWarnings("unchecked") List<Integer>[] tasks=new List[in.m()];for(int j=0;j<in.m();j++)tasks[j]=new ArrayList<>();
        for(int i=0;i<in.n();i++){if(x[i]<0||x[i]>=in.m())throw new IllegalArgumentException("VM index");tasks[x[i]].add(i);}
        double[] busy=new double[in.m()], finish=new double[in.n()];
        for(int j=0;j<in.m();j++){
            final Instance z=in;tasks[j].sort(Comparator.comparingLong((Integer i)->z.deadline[i]).thenComparingInt(i->i));
            for(int i:tasks[j]){busy[j]+=in.length[i]/(double)in.mips[j];finish[i]=busy[j];}
        }
        if(simulatedFinish!=null)finish=simulatedFinish.clone();
        double makespan=Arrays.stream(finish).max().orElse(0);
        double sum=Arrays.stream(busy).sum(), mean=sum/in.m(), variance=0,tardiness=0;int missed=0;
        for(double v:busy)variance+=(v-mean)*(v-mean);
        for(int i=0;i<in.n();i++){double late=Math.max(0,finish[i]-in.deadline[i]);tardiness+=late/in.deadline[i];if(late>1e-8)missed++;}
        double cv=mean>0?Math.sqrt(variance/in.m())/mean:0;
        tardiness/=in.n();double fitness=A*makespan/m0+B*cv+C*tardiness;
        return new Metrics(makespan,tardiness,missed/(double)in.n(),cv,fitness,finish);
    }
    static Candidate candidate(Budget b,int[] x){return new Candidate(x,b.score(x));}
    static int[] randomMap(Instance in,Random r){int[] x=new int[in.n()];for(int i=0;i<x.length;i++)x[i]=r.nextInt(in.m());return x;}
    static List<Candidate> initial(Budget b,Random r,boolean seeded){
        List<Candidate> p=new ArrayList<>();if(seeded)p.add(candidate(b,minMin(b.in)));
        while(p.size()<POP)p.add(candidate(b,randomMap(b.in,r)));return p;
    }
    static Candidate best(Collection<Candidate> p){return Collections.min(p,ORDER);}
    static Candidate tournament(List<Candidate> p,Random r){Candidate z=null;for(int k=0;k<3;k++){Candidate c=p.get(r.nextInt(p.size()));if(z==null||ORDER.compare(c,z)<0)z=c;}return z;}
    static int[] cross(int[] a,int[] b,Random r,boolean twoPoint){
        int[] x=a.clone();int lo=r.nextInt(a.length),hi=r.nextInt(a.length);if(lo>hi){int t=lo;lo=hi;hi=t;}
        for(int i=0;i<a.length;i++)if(twoPoint?(i>=lo&&i<=hi):r.nextBoolean())x[i]=b[i];return x;
    }
    static void mutate(int[] x,int m,Random r,double probability){
        for(int i=0;i<x.length;i++)if(r.nextDouble()<probability){int next=r.nextInt(m-1);x[i]=next>=x[i]?next+1:next;}
    }
    static Candidate repair(Budget b,Candidate source){
        Instance in=b.in;double[] busy=new double[in.m()];for(int i=0;i<in.n();i++)busy[source.x[i]]+=in.length[i]/(double)in.mips[source.x[i]];
        int bottleneck=0;for(int j=1;j<in.m();j++)if(busy[j]>busy[bottleneck])bottleneck=j;
        final int from=bottleneck;
        List<Integer> jobs=IntStream.range(0,in.n()).filter(i->source.x[i]==from).boxed().collect(Collectors.toList());
        jobs.sort((i,k)->Long.compare(in.length[k],in.length[i]));Candidate answer=source;
        for(int index=0;index<Math.min(5,jobs.size())&&b.room();index++){
            int task=jobs.get(index);
            for(int j=0;j<in.m()&&b.room();j++)if(j!=from){int[] x=source.x.clone();x[task]=j;Candidate trial=candidate(b,x);if(ORDER.compare(trial,answer)<0)answer=trial;}
        }
        return answer;
    }
    /** Discrete adaptation of the DE + cat seeking/tracing components in DECSO (2025).
     * This omits that paper's migration model and is not an exact reproduction. */
    static Run decsoInspired(Instance in,double m0,long seed,long start){
        Random r=new Random(seed);Budget b=new Budget(in,m0,BUDGET);List<Candidate> p=initial(b,r,false);
        Candidate global=best(p).copy();
        while(b.room()){
            for(int k=0;k<POP&&b.room();k++){
                int a=r.nextInt(POP),c=r.nextInt(POP),d=r.nextInt(POP);
                while(c==a)c=r.nextInt(POP);while(d==a||d==c)d=r.nextInt(POP);
                int[] x=p.get(k).x.clone();int forced=r.nextInt(in.n());
                for(int i=0;i<x.length;i++)if(i==forced||r.nextDouble()<.6){
                    int v=(int)Math.round(p.get(a).x[i]+.5*(p.get(c).x[i]-p.get(d).x[i]));
                    x[i]=Math.floorMod(v,in.m());
                }
                Candidate trial=candidate(b,x);
                if(ORDER.compare(trial,p.get(k))<0)p.set(k,trial);
                if(!b.room())break;
                // Cat seeking perturbs a few assignments; tracing copies some elite assignments.
                x=p.get(k).x.clone();
                if(r.nextBoolean())for(int step=0;step<Math.min(3,in.n());step++)x[r.nextInt(in.n())]=r.nextInt(in.m());
                else for(int i=0;i<in.n();i++)if(r.nextDouble()<.20)x[i]=global.x[i];
                trial=candidate(b,x);if(ORDER.compare(trial,p.get(k))<0)p.set(k,trial);
                if(ORDER.compare(p.get(k),global)<0)global=p.get(k).copy();
            }
        }
        return new Run(global.x,global.metrics,b.used,(System.nanoTime()-start)/1e6);
    }
    static int pheromoneChoice(double[] values,Random r){
        double total=0;for(double v:values)total+=v;double draw=r.nextDouble()*total;
        for(int j=0;j<values.length;j++){draw-=values[j];if(draw<=0)return j;}return values.length-1;
    }
    /** Discrete ACO construction plus PSO personal/global copying inspired by EcoTaskOpt (2026).
     * Thermal, energy, networking and VM scaling components are outside this batch model. */
    static Run ecoInspired(Instance in,double m0,long seed,long start){
        Random r=new Random(seed);Budget b=new Budget(in,m0,BUDGET);List<Candidate> p=initial(b,r,false),personal=new ArrayList<>();
        for(Candidate z:p)personal.add(z.copy());Candidate global=best(p).copy();
        double[][] tau=new double[in.n()][in.m()];for(double[] row:tau)Arrays.fill(row,1.0);
        int stall=0;
        while(b.room()){
            double evaporation=Math.min(.35,.10+.02*stall);
            for(int i=0;i<in.n();i++)for(int j=0;j<in.m();j++)tau[i][j]=Math.max(.05,(1-evaporation)*tau[i][j]);
            List<Candidate> ranked=new ArrayList<>(personal);ranked.sort(ORDER);
            for(int k=0;k<Math.min(5,ranked.size());k++)for(int i=0;i<in.n();i++)tau[i][ranked.get(k).x[i]]+=.5/(.1+ranked.get(k).metrics.fitness);
            boolean improved=false;
            for(int k=0;k<POP&&b.room();k++){
                int[] x=p.get(k).x.clone();double[] busy=new double[in.m()];
                for(int i=0;i<in.n();i++){
                    double roll=r.nextDouble();
                    if(roll<.20)x[i]=personal.get(k).x[i];
                    else if(roll<.50)x[i]=global.x[i];
                    else if(roll<.85){
                        double[] weight=new double[in.m()];
                        for(int j=0;j<in.m();j++)weight[j]=tau[i][j]/(1+busy[j]+in.length[i]/(double)in.mips[j]);
                        x[i]=pheromoneChoice(weight,r);
                    }else x[i]=r.nextInt(in.m());
                    busy[x[i]]+=in.length[i]/(double)in.mips[x[i]];
                }
                Candidate trial=candidate(b,x);p.set(k,trial);
                if(ORDER.compare(trial,personal.get(k))<0)personal.set(k,trial.copy());
                if(ORDER.compare(trial,global)<0){global=trial.copy();improved=true;}
            }
            stall=improved?0:stall+1;
        }
        return new Run(global.x,global.metrics,b.used,(System.nanoTime()-start)/1e6);
    }
    static Run optimize(Instance in,String method,double m0,long randomSeed){
        long start=System.nanoTime();
        if(method.equals("RoundRobin")||method.equals("MinMin")){
            int[] x=method.equals("MinMin")?minMin(in):roundRobin(in);return new Run(x,measure(in,x,m0,null),0,(System.nanoTime()-start)/1e6);
        }
        if(method.equals("DECSO_inspired"))return decsoInspired(in,m0,randomSeed,start);
        if(method.equals("EcoTaskOpt_inspired"))return ecoInspired(in,m0,randomSeed,start);
        Random r=new Random(randomSeed);Budget b=new Budget(in,m0,BUDGET);
        boolean proposal=method.equals("SA_DGSSA_BR")||method.equals("NoRepair")||method.equals("NoAdapt");
        boolean hybrid=proposal||method.equals("GA_SSA_reconstruction");
        boolean ga=hybrid||method.equals("GA");
        List<Candidate> p=initial(b,r,proposal);Candidate global=best(p).copy();int stall=0;
        while(b.room()){
            p.sort(ORDER);List<Candidate> next=new ArrayList<>();next.add(p.get(0));next.add(p.get(1));
            double mutation=proposal&&!method.equals("NoAdapt")?Math.min(.30,.05+.25*stall/10.0):.10;
            // The reference reconstruction uses tournament=3, two-point crossover, mutation=.1, elitism=2.
            while(next.size()<POP&&b.room()){
                int[] x;
                if(ga)x=cross(tournament(p,r).x,tournament(p,r).x,r,!proposal);
                else x=p.get(r.nextInt(p.size())).x.clone();
                if(ga)mutate(x,in.m(),r,mutation);
                next.add(candidate(b,x));
            }
            if(!b.room()){p=next;break;}
            if(!ga){ // Discrete SSA alone: ordered leader and follower copy operations.
                next=new ArrayList<>(p);
            }
            if(hybrid||!ga){
                next.sort(ORDER);Candidate elite=best(next);double q=.20+.60*Math.min(1.0,b.used/(double)BUDGET);
                for(int k=1;k<next.size()&&b.room();k++){
                    int[] x=next.get(k).x.clone();int[] lead=(k==1?elite.x:next.get(k-1).x);
                    for(int i=0;i<x.length;i++){
                        if(k==1){if(r.nextDouble()<q)x[i]=elite.x[i];else if(r.nextDouble()<.08)x[i]=r.nextInt(in.m());}
                        else if(r.nextBoolean())x[i]=lead[i];
                    }
                    Candidate changed=candidate(b,x);if(ORDER.compare(changed,next.get(k))<0)next.set(k,changed);
                }
            }
            Candidate current=best(next);
            if(proposal&&!method.equals("NoRepair")&&b.room()){
                Candidate fixed=repair(b,current);if(ORDER.compare(fixed,current)<0){next.set(next.indexOf(current),fixed);current=fixed;}
            }
            if(ORDER.compare(current,global)<0){global=current.copy();stall=0;}else stall++;
            p=next;
        }
        Candidate finalBest=best(p);if(ORDER.compare(finalBest,global)<0)global=finalBest;
        return new Run(global.x,global.metrics,b.used,(System.nanoTime()-start)/1e6);
    }
    static double[] simulate(Instance in,int[] x){
        CloudSimPlus sim=new CloudSimPlus(0.0001);DatacenterBrokerSimple broker=new DatacenterBrokerSimple(sim);
        List<Pe> pes=new ArrayList<>();for(int j=0;j<in.m();j++)pes.add(new PeSimple(2000));
        new DatacenterSimple(sim,List.of(new HostSimple(Math.max(8192L,1024L*in.m()),1000000,10000000,pes)));
        List<Vm> vms=new ArrayList<>();for(int j=0;j<in.m();j++)vms.add(new VmSimple(j,in.mips[j],1).setRam(512).setBw(1000).setSize(1000).setCloudletScheduler(new CloudletSchedulerSpaceShared()));
        broker.submitVmList(vms);
        List<Cloudlet> cloudlets=new ArrayList<>();
        for(int j=0;j<in.m();j++){
            final int vm=j;
            List<Integer> jobs=IntStream.range(0,in.n()).filter(i->x[i]==vm).boxed().collect(Collectors.toList());
            jobs.sort(Comparator.comparingLong((Integer i)->in.deadline[i]).thenComparingInt(i->i));
            for(int i:jobs)cloudlets.add(new CloudletSimple(i,in.length[i],1).setUtilizationModelCpu(new UtilizationModelFull()).setVm(vms.get(j)));
        }
        broker.submitCloudletList(cloudlets);sim.start();
        double[] finish=new double[in.n()];boolean[] seen=new boolean[in.n()];
        for(Cloudlet c:broker.getCloudletFinishedList()){int id=(int)c.getId();finish[id]=c.getFinishTime();seen[id]=true;}
        for(boolean v:seen)if(!v)throw new IllegalStateException("Cloudlet failed to finish");
        return finish;
    }
    static void writeInstance(Path dir,Instance in)throws IOException{
        Files.createDirectories(dir);try(PrintWriter w=new PrintWriter(Files.newBufferedWriter(dir.resolve("instance.csv")))){
            w.println("task_id,length_mi,deadline_s");for(int i=0;i<in.n();i++)w.printf(Locale.US,"%d,%d,%d%n",i,in.length[i],in.deadline[i]);
        }
        try(PrintWriter w=new PrintWriter(Files.newBufferedWriter(dir.resolve("vms.csv")))){
            w.println("vm_id,mips");for(int j=0;j<in.m();j++)w.printf(Locale.US,"%d,%d%n",j,in.mips[j]);
        }
    }
    static void selfTest(){
        Instance small=new Instance(new long[]{4000,2000,6000},new long[]{10,10,10},new long[]{1000,2000},"manual",0);
        int[] x={0,1,1};Metrics analytical=measure(small,x,4,null);if(Math.abs(analytical.makespan-4)>1e-9)throw new AssertionError("analytical makespan");
        double[] sim=simulate(small,x);double offset=sim[0]-4;
        if(Math.abs((sim[2]-sim[1])-3)>0.05||Math.abs(offset)>0.05)throw new AssertionError("CloudSim schedule mismatch: "+Arrays.toString(sim));
        System.out.println("Self-test passed: analytical M=4s, CloudSim finish="+Arrays.toString(sim));
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("selftest")){selfTest();return;}
        if(args.length>1&&args[0].equals("example")){
            Path dir=Path.of(args[1]);Instance in=generate(10,5,"uniform",130000L);writeInstance(dir,in);
            double m0=measure(in,minMin(in),1,null).makespan;
            try(PrintWriter w=new PrintWriter(Files.newBufferedWriter(dir.resolve("schedule.csv")))){
                w.println("method,task_id,vm_id,length_mi,vm_mips,deadline_s,cloudsim_finish_s");
                for(String method:new String[]{"MinMin","GA_SSA_reconstruction","SA_DGSSA_BR"}){
                    Run run=optimize(in,method,m0,130000L+method.hashCode());double[] finish=simulate(in,run.assignment);
                    for(int i=0;i<in.n();i++)w.printf(Locale.US,"%s,%d,%d,%d,%d,%d,%.6f%n",method,i,run.assignment[i],in.length[i],in.mips[run.assignment[i]],in.deadline[i],finish[i]);
                }
            }
            System.out.println("Wrote example schedules to "+dir);return;
        }
        if(args.length<1)throw new IllegalArgumentException("Usage: java -jar scheduler.jar output-dir [seeds=30]");
        Path out=Path.of(args[0]);Files.createDirectories(out);int seeds=args.length>1?Integer.parseInt(args[1]):30;
        try(PrintWriter w=new PrintWriter(Files.newBufferedWriter(out.resolve("runs.csv")))){
            w.println("workload,tasks,vms,seed,method,makespan_s,tardiness,deadline_miss_rate,load_cv,fitness,optimizer_ms,fitness_evaluations,analytic_makespan_s,cloudsim_minus_analytic_s");
            for(String workload:new String[]{"uniform","heavy"})for(int n:new int[]{10,50,100,200,500}){
                int m=n<=10?5:n<=100?10:20;
                for(int s=0;s<seeds;s++){
                    long seed=120000L+n*1000L+s+(workload.equals("heavy")?10000000L:0L);
                    Instance in=generate(n,m,workload,seed);int[] min=minMin(in);double m0=measure(in,min,1,null).makespan;
                    List<String> methodOrder=new ArrayList<>(Arrays.asList(METHODS));
                    Collections.shuffle(methodOrder,new Random(seed));
                    for(String method:methodOrder){
                        Run run=optimize(in,method,m0,seed+method.hashCode());double[] simFinish=simulate(in,run.assignment);
                        Metrics measured=measure(in,run.assignment,m0,simFinish);
                        double diff=measured.makespan-run.metrics.makespan;
                        if(!Double.isFinite(diff))throw new IllegalStateException("Nonfinite simulation result");
                        w.printf(Locale.US,"%s,%d,%d,%d,%s,%.6f,%.9f,%.9f,%.9f,%.9f,%.6f,%d,%.6f,%.6f%n",workload,n,m,seed,method,measured.makespan,measured.tardiness,measured.missRate,measured.imbalance,measured.fitness,run.optimizerMs,run.evals,run.metrics.makespan,diff);
                    }
                    w.flush();
                }
                System.out.printf("Finished %s, n=%d, %d seeds%n",workload,n,seeds);
            }
        }
    }
}
